package com.altaran.watchwallet.chain.kaspa

import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.CashAddrCodec
import com.altaran.watchwallet.chain.common.UtxoIo
import com.altaran.watchwallet.chain.common.UtxoTx
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.arr
import com.altaran.watchwallet.chain.common.asList
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.long
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.requireBigInteger
import com.altaran.watchwallet.chain.common.requireList
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.chain.common.utxoChainTransaction
import com.altaran.watchwallet.core.endpoints.BundledEndpoints
import com.altaran.watchwallet.core.endpoints.EndpointProtocol
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
import kotlin.time.Duration.Companion.milliseconds

object KaspaChain {
    val ID = ChainId("kaspa")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.KASPA,
        name = "Kaspa",
        nativeSymbol = "KAS",
        nativeDecimals = 8,
        nativePriceId = "kaspa",
        explorerTxUrl = "https://explorer.kaspa.org/txs/{tx}",
        explorerAddressUrl = "https://explorer.kaspa.org/addresses/{address}",
        color = 0xFF70C7BA,
    )

    val KAS: Asset = nativeAsset(CHAIN)
}

/**
 * Kaspa mainnet addresses: `kaspa:` + CashAddr-style payload (40-bit checksum) whose first byte
 * is the version: 0 = Schnorr public key (32 bytes), 1 = ECDSA public key (33 bytes),
 * 8 = script hash (32 bytes). The prefix may be omitted; testnet/devnet prefixes are rejected.
 */
object KaspaAddresses {
    const val PREFIX = "kaspa"
    private val PAYLOAD_SIZES = mapOf(0 to 32, 1 to 33, 8 to 32)

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        if (':' in text && !text.lowercase().startsWith("$PREFIX:")) return null
        val (prefix, payload) = CashAddrCodec.decode(text, PREFIX) ?: return null
        if (prefix != PREFIX || payload.isEmpty()) return null
        val expected = PAYLOAD_SIZES[payload[0].toInt() and 0xFF] ?: return null
        if (payload.size != expected + 1) return null
        return WatchTarget.Address(ChainFamily.KASPA, KaspaChain.ID, CashAddrCodec.encode(PREFIX, payload))
    }

    /** Encodes an address (used in tests). */
    fun encode(version: Int, data: ByteArray): String = CashAddrCodec.encode(
        PREFIX,
        byteArrayOf(version.toByte()) + data,
    )
}

/**
 * Kaspa via the community REST API (api.kaspa.org): balance, transaction count and full
 * transactions with resolved inputs, turned into UTXO-style deltas (fee included for the payer).
 */
class KaspaAdapter(
    private val http: HttpClient,
    apiUrl: String = BundledEndpoints.first(KaspaChain.ID, EndpointProtocol.KASPA_REST),
) : ChainAdapter {
    private val base = apiUrl.trimEnd('/')
    private val throttle = Throttle(maxConcurrent = 2, minInterval = 150.milliseconds, maxJitter = 100.milliseconds)

    override val family = ChainFamily.KASPA
    override val chains = listOf(KaspaChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("Kaspa") {
        val address = address(target)
        val balance = balance(address)
        val count = get("$base/addresses/$address/transactions-count").long("total") ?: 0L
        val active = count > 0
        ProbeResult(
            active,
            if (active) listOf(KaspaChain.ID) else emptyList(),
            listOf(amountOf(KaspaChain.KAS, balance)),
            count.toInt(),
        )
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("Kaspa") {
        val address = address(target)
        val balances = listOf(amountOf(KaspaChain.KAS, balance(address)))
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES

        val txs = mutableListOf<UtxoTx>()
        var truncated = false
        var offset = 0
        paging@ for (page in 0 until maxPages) {
            val items = get("$base/addresses/$address/full-transactions") {
                parameter("limit", PAGE_SIZE)
                parameter("offset", offset)
                parameter("resolve_previous_outpoints", "light")
            }.requireList("Kaspa transactions")
            for (item in items) {
                val tx = toUtxoTx(item) ?: continue
                if (tx.confirmed && tx.txid in knownTransactions) break@paging
                txs += tx
            }
            offset += PAGE_SIZE
            // A transaction the service cannot resolve shortens its page, also before the end:
            // only the count tells, since later syncs would never come back for what is skipped.
            if (items.size < PAGE_SIZE) {
                val total = get("$base/addresses/$address/transactions-count").long("total")
                if (total == null) truncated = true
                if (total == null || offset >= total) break
            }
            if (page == maxPages - 1) truncated = true
        }
        val ours = setOf(address)
        val transactions = txs.mapNotNull { utxoChainTransaction(KaspaChain.CHAIN, KaspaChain.KAS, it, ours) }
        val active = transactions.isNotEmpty() || !balances.first().amount.isZero
        SyncResult(
            balances,
            transactions,
            activeChains = if (active) listOf(KaspaChain.ID) else emptyList(),
            historyTruncated = truncated,
        )
    }

    private suspend fun balance(address: String): BigInteger =
        requireBigInteger(get("$base/addresses/$address/balance").str("balance"), "Kaspa balance")

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address?.let { KaspaAddresses.parse(it)?.address ?: it }
            ?: throw ChainDataException("Kaspa supports addresses only")

    private suspend fun get(
        url: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
        },
    ): JsonElement =
        throttle.run { http.get(url, block).body<JsonElement>() }

    private companion object {
        const val PAGE_SIZE = 50
        const val FIRST_SYNC_PAGES = 2
        const val MAX_PAGES = 20
    }
}

internal fun toUtxoTx(item: JsonElement): UtxoTx? {
    val id = item.str("transaction_id") ?: return null
    val inputs = item.arr("inputs").map {
        UtxoIo(
            it.str("previous_outpoint_address"),
            it.long("previous_outpoint_amount") ?: 0L,
        )
    }
    val outputs = item.arr("outputs").map { UtxoIo(it.str("script_public_key_address"), it.long("amount") ?: 0L) }
    val fee = (inputs.sumOf { it.value } - outputs.sumOf { it.value }).coerceAtLeast(0L)
    return UtxoTx(
        txid = id,
        blockTime = item.long("block_time")?.let { it / 1_000 },
        confirmed = item.str("is_accepted") == "true",
        fee = fee,
        inputs = inputs,
        outputs = outputs,
    )
}
