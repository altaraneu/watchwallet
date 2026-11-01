package com.altaran.watchwallet.chain.tezos

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.Base58Checks
import com.altaran.watchwallet.chain.common.accountTransaction
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.asList
import com.altaran.watchwallet.chain.common.at
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.long
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.parseBigInteger
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.chain.common.tokenAsset
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object TezosChain {
    val ID = ChainId("tezos")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.TEZOS,
        name = "Tezos",
        nativeSymbol = "XTZ",
        nativeDecimals = 6,
        nativePriceId = "tezos",
        explorerTxUrl = "https://tzkt.io/{tx}",
        explorerAddressUrl = "https://tzkt.io/{address}",
        color = 0xFF2C7DF7,
    )

    val XTZ: Asset = nativeAsset(CHAIN, name = "Tezos")

    const val DEFAULT_TZKT_URL = "https://api.tzkt.io/v1"
}

/** Tezos addresses: Base58Check of a 3-byte prefix + 20-byte hash (`tz1`–`tz4` accounts, `KT1` contracts). */
object TezosAddresses {
    private val PREFIXES: Map<String, ByteArray> = mapOf(
        "tz1" to byteArrayOf(0x06, 0xA1.toByte(), 0x9F.toByte()),
        "tz2" to byteArrayOf(0x06, 0xA1.toByte(), 0xA1.toByte()),
        "tz3" to byteArrayOf(0x06, 0xA1.toByte(), 0xA4.toByte()),
        "tz4" to byteArrayOf(0x06, 0xA1.toByte(), 0xA6.toByte()),
        "KT1" to byteArrayOf(0x02, 0x5A, 0x79),
    )

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        if (text.length != 36) return null
        val prefix = PREFIXES[text.take(3)] ?: return null
        val payload = Base58Checks.decode(text) ?: return null
        if (payload.size != 23 || !payload.copyOfRange(0, 3).contentEquals(prefix)) return null
        return WatchTarget.Address(ChainFamily.TEZOS, TezosChain.ID, text)
    }

    fun isContract(address: String): Boolean = address.startsWith("KT1")
}

/**
 * Tezos via TzKT: XTZ balance (staked and delegated tez remain part of the balance), FA1.2/FA2
 * fungible token balances and XTZ history (transactions, originations, delegations, reveals) with
 * fees for the sender. Token transfer history is not included (TzKT token transfers carry no
 * operation hash), so token balances change without history entries.
 */
class TezosAdapter(
    private val http: HttpClient,
    tzktUrl: String = TezosChain.DEFAULT_TZKT_URL,
) : ChainAdapter {
    private val base = tzktUrl.trimEnd('/')
    private val throttle = Throttle(maxConcurrent = 3, minInterval = 100.milliseconds, maxJitter = 80.milliseconds)

    override val family = ChainFamily.TEZOS
    override val chains = listOf(TezosChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("TzKT") {
        val address = address(target)
        val account = get("$base/accounts/$address")
        val active = account.str("type") != "empty"
        val balances = listOf(amountOf(TezosChain.XTZ, account.str("balance"))) + if (active) tokens(address) else emptyList()
        ProbeResult(active, if (active) listOf(TezosChain.ID) else emptyList(), balances, account.long("numTransactions")?.toInt())
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("TzKT") {
        val address = address(target)
        val account = get("$base/accounts/$address")
        if (account.str("type") == "empty") {
            return@chainCall SyncResult(listOf(amountOf(TezosChain.XTZ, BigInteger.ZERO)), emptyList())
        }
        val balances = listOf(amountOf(TezosChain.XTZ, account.str("balance"))) + tokens(address)
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES

        val operations = mutableListOf<JsonElement>()
        var lastId: Long? = null
        var pages = 0
        var truncated = false
        paging@ while (true) {
            val page = get("$base/accounts/$address/operations") {
                parameter("type", "transaction,origination,delegation,reveal")
                parameter("limit", PAGE_SIZE)
                parameter("sort", 1)
                lastId?.let { parameter("lastId", it) }
            }.asList
            for (op in page) {
                if (op.str("hash") in knownTransactions) break@paging
                operations += op
            }
            if (page.size < PAGE_SIZE) break
            lastId = page.last().long("id") ?: break
            if (++pages >= maxPages) {
                truncated = true
                break
            }
        }
        SyncResult(balances, groupOperations(operations, address), activeChains = listOf(TezosChain.ID), historyTruncated = truncated)
    }

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("Tezos supports addresses only")

    private suspend fun tokens(address: String): List<AssetAmount> {
        val list = get("$base/tokens/balances") {
            parameter("account", address)
            parameter("balance.gt", 0)
            parameter("limit", MAX_TOKENS)
        }.asList
        return list.mapNotNull { entry ->
            val token = entry.at("token")
            val contract = token.at("contract").str("address") ?: return@mapNotNull null
            val decimals = token.at("metadata").str("decimals")?.toIntOrNull() ?: return@mapNotNull null
            val tokenId = token.str("tokenId") ?: "0"
            val raw = parseBigInteger(entry.str("balance"))
            if (raw.isZero()) return@mapNotNull null
            val asset = tokenAsset(
                chainId = TezosChain.ID,
                contract = "$contract-$tokenId",
                symbol = token.at("metadata").str("symbol"),
                name = token.at("metadata").str("name"),
                decimals = decimals,
            )
            amountOf(asset, raw)
        }
    }

    private suspend fun get(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): JsonElement =
        throttle.run { http.get(url, block).body<JsonElement>() }

    private companion object {
        const val PAGE_SIZE = 100
        const val FIRST_SYNC_PAGES = 2
        const val MAX_PAGES = 20
        const val MAX_TOKENS = 100
    }
}

/**
 * Net XTZ change of [address] per operation group (hash): received and sent amounts of applied
 * operations, minus baker/storage/allocation fees paid as sender.
 */
internal fun groupOperations(operations: List<JsonElement>, address: String): List<ChainTransaction> {
    class Group(var delta: BigInteger, var fee: BigInteger?, val time: Instant, var confirmed: Boolean)
    val groups = LinkedHashMap<String, Group>()
    for (op in operations) {
        val hash = op.str("hash") ?: continue
        val time = op.str("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Clock.System.now()
        val group = groups.getOrPut(hash) { Group(BigInteger.ZERO, null, time, true) }
        val applied = op.str("status") == "applied"
        val amount = if (applied && op.str("type") == "transaction") parseBigInteger(op.str("amount")) else BigInteger.ZERO
        val sender = op.at("sender").str("address")
        val receiver = op.at("target").str("address")
        if (receiver == address) group.delta = group.delta + amount
        if (sender == address) {
            val fee = parseBigInteger(op.str("bakerFee")) + parseBigInteger(op.str("storageFee")) + parseBigInteger(op.str("allocationFee"))
            group.delta = group.delta - amount - fee
            group.fee = (group.fee ?: BigInteger.ZERO) + fee
        }
    }
    return groups.mapNotNull { (hash, g) ->
        if (g.delta.isZero() && g.fee == null) return@mapNotNull null
        accountTransaction(TezosChain.ID, hash, g.time, TezosChain.XTZ, g.delta, g.fee, g.confirmed, isSelfTransfer = false)
    }
}
