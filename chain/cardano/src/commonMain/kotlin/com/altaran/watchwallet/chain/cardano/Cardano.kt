package com.altaran.watchwallet.chain.cardano

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.Bech32Codec
import com.altaran.watchwallet.chain.common.Hex
import com.altaran.watchwallet.chain.common.accountTransaction
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.arr
import com.altaran.watchwallet.chain.common.asList
import com.altaran.watchwallet.chain.common.at
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.content
import com.altaran.watchwallet.chain.common.long
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.parseBigInteger
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.chain.common.tokenAsset
import com.altaran.watchwallet.chain.common.tokenPriceId
import com.altaran.watchwallet.chain.common.withRateLimitBackoff
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object CardanoChain {
    val ID = ChainId("cardano")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.CARDANO,
        name = "Cardano",
        nativeSymbol = "ADA",
        nativeDecimals = 6,
        nativePriceId = "cardano",
        explorerTxUrl = "https://cardanoscan.io/transaction/{tx}",
        explorerAddressUrl = "https://cardanoscan.io/address/{address}",
        color = 0xFF0033AD,
    )

    val ADA: Asset = nativeAsset(CHAIN)

    const val DEFAULT_API_URL = "https://api.koios.rest/api/v1"
    const val COINGECKO_PLATFORM = "cardano"
}

/**
 * Cardano Shelley mainnet addresses (CIP-19): reward addresses `stake1…` (cover the whole wallet)
 * and payment addresses `addr1…` (Bech32 longer than 90 characters, decoded without the BIP-173
 * length limit). Byron-era Base58 addresses are not supported.
 */
object CardanoAddresses {
    private const val MAINNET = 1
    private const val HASH = 28

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        val (hrp, bytes) = Bech32Codec.decodeBytes(text) ?: return null
        if (bytes.isEmpty() || (bytes[0].toInt() and 0x0F) != MAINNET) return null
        val type = (bytes[0].toInt() and 0xF0) ushr 4
        val valid = when (hrp) {
            "stake" -> (type == 14 || type == 15) && bytes.size == 1 + HASH

            "addr" -> when (type) {
                in 0..3 -> bytes.size == 1 + 2 * HASH
                4, 5 -> bytes.size in (1 + HASH + 3)..(1 + HASH + 30)
                6, 7 -> bytes.size == 1 + HASH
                else -> false
            }

            else -> false
        }
        return if (valid) WatchTarget.Address(ChainFamily.CARDANO, CardanoChain.ID, text.lowercase()) else null
    }

    fun isStakeAddress(address: String): Boolean = address.lowercase().startsWith("stake1")

    /**
     * Reward (stake) address of a base address (`addr1q…`/`addr1z…`/`addr1y…`/`addr1x…`), which
     * covers every address of the same wallet; `null` for enterprise/pointer addresses.
     */
    fun stakeAddressOf(address: String): String? {
        if (parse(address) == null) return null
        val (hrp, bytes) = Bech32Codec.decodeBytes(address.trim()) ?: return null
        if (hrp != "addr") return null
        val type = (bytes[0].toInt() and 0xF0) ushr 4
        if (type !in 0..3) return null
        val scriptStake = type == 2 || type == 3
        val header = ((if (scriptStake) 0xF0 else 0xE0) or MAINNET).toByte()
        return Bech32Codec.encodeBytes("stake", byteArrayOf(header) + bytes.copyOfRange(1 + HASH, 1 + 2 * HASH))
    }
}

/**
 * Cardano via Koios (public tier, no key). A `stake1…` target reports the whole wallet: UTXO
 * value plus available rewards, all native tokens, and transactions of every address sharing the
 * stake key. An `addr1…` target reports that single address.
 *
 * Deltas come from `tx_info` inputs/outputs; for stake targets reward withdrawals are subtracted
 * because rewards are already part of the balance. Reward accrual itself (once per epoch) is not
 * a transaction and therefore not in the history.
 */
class CardanoAdapter(
    private val http: HttpClient,
    apiUrl: String = CardanoChain.DEFAULT_API_URL,
) : ChainAdapter {
    private val base = apiUrl.trimEnd('/')
    private val throttle = Throttle(maxConcurrent = 2, minInterval = 250.milliseconds, maxJitter = 100.milliseconds)

    override val family = ChainFamily.CARDANO
    override val chains = listOf(CardanoChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("Koios") {
        val address = address(target)
        val state = state(address)
        ProbeResult(
            hasActivity = state != null,
            activeChains = if (state != null) listOf(CardanoChain.ID) else emptyList(),
            balances = state?.balances ?: listOf(amountOf(CardanoChain.ADA, BigInteger.ZERO)),
            transactionCount = null,
        )
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("Koios") {
        val address = address(target)
        val state = state(address)
            ?: return@chainCall SyncResult(listOf(amountOf(CardanoChain.ADA, BigInteger.ZERO)), emptyList())
        val stake = CardanoAddresses.isStakeAddress(address)

        val hashes = mutableListOf<String>()
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES
        var truncated = true
        paging@ for (page in 0 until maxPages) {
            val rows = if (stake) {
                post("account_txs", buildJsonObject { put("_stake_address", address) }, page)
            } else {
                post(
                    "address_txs",
                    buildJsonObject {
                        put("_addresses", JsonArray(listOf(JsonPrimitive(address))))
                    },
                    page,
                )
            }.asList
            for (row in rows) {
                val hash = row.str("tx_hash") ?: continue
                if (hash in knownTransactions) {
                    truncated = false
                    break@paging
                }
                hashes += hash
            }
            if (rows.size < PAGE_SIZE) {
                truncated = false
                break
            }
        }
        val assets = state.balances.associate { (it.asset.contract ?: "") to it.asset }
        val transactions = hashes.distinct().chunked(TX_INFO_BATCH).flatMap { batch ->
            val body = buildJsonObject {
                put("_tx_hashes", JsonArray(batch.map { JsonPrimitive(it) }))
                put("_inputs", true)
                put("_assets", true)
                put("_withdrawals", true)
            }
            post("tx_info", body, page = null).asList.flatMap { parseCardanoTransaction(it, address, stake, assets) }
        }
        SyncResult(state.balances, transactions, activeChains = listOf(CardanoChain.ID), historyTruncated = truncated)
    }

    private class State(val balances: List<AssetAmount>)

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("Cardano supports addresses only")

    /** Balances, or `null` when the address/stake key has never been used. */
    private suspend fun state(address: String): State? {
        val list = JsonArray(listOf(JsonPrimitive(address)))
        return if (CardanoAddresses.isStakeAddress(address)) {
            val info =
                post("account_info", buildJsonObject { put("_stake_addresses", list) }, null).asList.firstOrNull()
                    ?: return null
            val tokens = post("account_assets", buildJsonObject { put("_stake_addresses", list) }, null).asList
            State(listOf(amountOf(CardanoChain.ADA, info.str("total_balance"))) + tokenBalances(tokens))
        } else {
            val info =
                post("address_info", buildJsonObject { put("_addresses", list) }, null).asList.firstOrNull()
                    ?: return null
            val tokens = info.arr("utxo_set").flatMap { it.arr("asset_list") }
            State(listOf(amountOf(CardanoChain.ADA, info.str("balance"))) + tokenBalances(tokens))
        }
    }

    private fun tokenBalances(rows: List<JsonElement>): List<AssetAmount> {
        val sums = LinkedHashMap<String, Pair<Asset, BigInteger>>()
        for (row in rows) {
            val asset = nativeToken(row) ?: continue
            val key = asset.contract!!
            sums[key] = asset to ((sums[key]?.second ?: BigInteger.ZERO) + parseBigInteger(row.str("quantity")))
        }
        return sums.values.filter { !it.second.isZero() }.map { amountOf(it.first, it.second) }
    }

    private suspend fun post(endpoint: String, body: JsonElement, page: Int?): JsonElement =
        withRateLimitBackoff(RATE_LIMIT_RETRIES) {
            throttle.run {
                http.post("$base/$endpoint") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    if (page != null) {
                        parameter("order", "block_height.desc")
                        parameter("limit", PAGE_SIZE)
                        parameter("offset", page * PAGE_SIZE)
                    }
                }.body<JsonElement>()
            }
        }

    private companion object {
        const val PAGE_SIZE = 100
        const val FIRST_SYNC_PAGES = 2
        const val MAX_PAGES = 20
        const val TX_INFO_BATCH = 20
        const val RATE_LIMIT_RETRIES = 3
    }
}

/** Native token from a Koios asset row (`policy_id`, `asset_name` hex, `decimals`). */
internal fun nativeToken(row: JsonElement): Asset? {
    val policy = row.str("policy_id") ?: return null
    val name = row.str("asset_name").orEmpty()
    val contract = policy + name
    val decoded = Hex.decode(name)?.takeIf { bytes ->
        bytes.isNotEmpty() && bytes.all { it in 0x20..0x7E }
    }?.decodeToString()
    val symbol = decoded ?: row.str("fingerprint")
    return tokenAsset(
        chainId = CardanoChain.ID,
        contract = contract,
        symbol = symbol,
        name = decoded ?: row.str("fingerprint"),
        decimals = row.str("decimals")?.toIntOrNull() ?: 0,
        priceId = tokenPriceId(CardanoChain.COINGECKO_PLATFORM, contract),
    )
}

/** Per-asset net effect of one `tx_info` entry on [address] (stake or payment address). */
internal fun parseCardanoTransaction(
    tx: JsonElement,
    address: String,
    stake: Boolean,
    assets: Map<String, Asset>,
): List<ChainTransaction> {
    val hash = tx.str("tx_hash") ?: return emptyList()
    val time = tx.long("block_time")?.let { Instant.fromEpochSeconds(it) } ?: Clock.System.now()
    fun ours(io: JsonElement) = if (stake) {
        io.str("stake_addr") == address
    } else {
        io.at("payment_addr", "bech32").content ==
            address
    }

    val inputs = tx.arr("inputs").filter(::ours)
    val outputs = tx.arr("outputs").filter(::ours)
    if (inputs.isEmpty() && outputs.isEmpty()) return emptyList()

    var ada = outputs.fold(BigInteger.ZERO) { s, io -> s + parseBigInteger(io.str("value")) } -
        inputs.fold(BigInteger.ZERO) { s, io -> s + parseBigInteger(io.str("value")) }
    if (stake) {
        tx.arr("withdrawals").filter { it.str("stake_addr") == address }.forEach {
            ada -=
                parseBigInteger(it.str("amount"))
        }
    }
    val tokenDeltas = LinkedHashMap<String, Pair<Asset, BigInteger>>()
    fun collect(list: List<JsonElement>, sign: Int) = list.forEach { io ->
        io.arr("asset_list").forEach { row ->
            val asset = nativeToken(row) ?: return@forEach
            val key = asset.contract!!
            val amount = parseBigInteger(row.str("quantity"))
            val previous = tokenDeltas[key]?.second ?: BigInteger.ZERO
            tokenDeltas[key] = (assets[key] ?: asset) to (if (sign > 0) previous + amount else previous - amount)
        }
    }
    collect(outputs, +1)
    collect(inputs, -1)

    val weFunded = inputs.isNotEmpty()
    val selfTransfer = weFunded && tx.arr("outputs").all(::ours)
    val result = mutableListOf<ChainTransaction>()
    if (!ada.isZero() || weFunded) {
        val fee = if (weFunded) parseBigInteger(tx.str("fee")) else null
        result +=
            accountTransaction(CardanoChain.ID, hash, time, CardanoChain.ADA, ada, fee, isSelfTransfer = selfTransfer)
    }
    tokenDeltas.values.filter { !it.second.isZero() }.forEach { (asset, delta) ->
        result += accountTransaction(CardanoChain.ID, hash, time, asset, delta, null)
    }
    return result
}
