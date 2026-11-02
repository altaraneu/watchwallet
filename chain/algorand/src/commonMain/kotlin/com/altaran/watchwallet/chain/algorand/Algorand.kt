package com.altaran.watchwallet.chain.algorand

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.Base32
import com.altaran.watchwallet.chain.common.accountTransaction
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.arr
import com.altaran.watchwallet.chain.common.at
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.long
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.notFoundAsNull
import com.altaran.watchwallet.chain.common.parseBigInteger
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.chain.common.tokenAsset
import com.altaran.watchwallet.chain.common.tokenPriceId
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
import org.kotlincrypto.hash.sha2.SHA512t
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object AlgorandChain {
    val ID = ChainId("algorand")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.ALGORAND,
        name = "Algorand",
        nativeSymbol = "ALGO",
        nativeDecimals = 6,
        nativePriceId = "algorand",
        explorerTxUrl = "https://allo.info/tx/{tx}",
        explorerAddressUrl = "https://allo.info/account/{address}",
        color = 0xFF000000,
    )

    val ALGO: Asset = nativeAsset(CHAIN)

    const val COINGECKO_PLATFORM = "algorand"
}

/** Algorand addresses: 58-char base32 of the 32-byte public key + last 4 bytes of its SHA-512/256. */
object AlgorandAddresses {
    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        if (text.length != 58) return null
        val raw = Base32.decode(text) ?: return null
        if (raw.size != 36) return null
        val key = raw.copyOfRange(0, 32)
        if (!checksum(key).contentEquals(raw.copyOfRange(32, 36))) return null
        return WatchTarget.Address(ChainFamily.ALGORAND, AlgorandChain.ID, text)
    }

    /** Address of a 32-byte public key (used in tests). */
    fun encode(publicKey: ByteArray): String {
        require(publicKey.size == 32)
        return Base32.encode(publicKey + checksum(publicKey))
    }

    internal fun sha512t256(data: ByteArray): ByteArray = SHA512t(256).digest(data)

    private fun checksum(key: ByteArray): ByteArray = sha512t256(key).let { it.copyOfRange(it.size - 4, it.size) }
}

/**
 * Algorand via the public AlgoNode indexer: ALGO and ASA balances, payment and asset-transfer
 * history (inner transactions of application calls included). Fees are part of the ALGO delta of
 * the sender. Applications' local state and staking rewards are not shown separately.
 */
class AlgorandAdapter(
    private val http: HttpClient,
    indexerUrl: String = BundledEndpoints.first(AlgorandChain.ID, EndpointProtocol.ALGORAND_INDEXER),
) : ChainAdapter {
    private val base = indexerUrl.trimEnd('/')
    private val throttle = Throttle(maxConcurrent = 3, minInterval = 100.milliseconds, maxJitter = 80.milliseconds)
    private val assetCache = HashMap<String, Asset?>()

    override val family = ChainFamily.ALGORAND
    override val chains = listOf(AlgorandChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("Algorand") {
        val account = account(address(target))
            ?: return@chainCall ProbeResult(
                false,
                emptyList(),
                listOf(amountOf(AlgorandChain.ALGO, BigInteger.ZERO)),
                0,
            )
        val balances = balances(account)
        val active = !balances.first().amount.isZero || (account.long("total-assets-opted-in") ?: 0L) > 0
        ProbeResult(active, if (active) listOf(AlgorandChain.ID) else emptyList(), balances, transactionCount = null)
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("Algorand") {
        val address = address(target)
        val account = account(address)
            ?: return@chainCall SyncResult(listOf(amountOf(AlgorandChain.ALGO, BigInteger.ZERO)), emptyList())
        val balances = balances(account)
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES

        val transactions = mutableListOf<ChainTransaction>()
        var next: String? = null
        var pages = 0
        var truncated = false
        paging@ while (true) {
            val page = get("$base/v2/accounts/$address/transactions") {
                parameter("limit", PAGE_SIZE)
                next?.let { parameter("next", it) }
            }
            val items = page.arr("transactions")
            for (tx in items) {
                val id = tx.str("id") ?: continue
                if (id in knownTransactions) break@paging
                transactions += parseAlgorandTransaction(tx, address) { assetId -> asa(assetId) }
            }
            next = page.str("next-token")
            if (next == null || items.size < PAGE_SIZE) break
            if (++pages >= maxPages) {
                truncated = true
                break
            }
        }
        SyncResult(balances, transactions, activeChains = listOf(AlgorandChain.ID), historyTruncated = truncated)
    }

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("Algorand supports addresses only")

    private suspend fun account(address: String): JsonElement? = notFoundAsNull {
        get("$base/v2/accounts/$address")
    }?.at("account")

    private suspend fun balances(account: JsonElement): List<AssetAmount> {
        val result = mutableListOf(amountOf(AlgorandChain.ALGO, account.str("amount")))
        for (holding in account.arr("assets").take(MAX_ASSETS)) {
            val id = holding.str("asset-id") ?: continue
            val raw = parseBigInteger(holding.str("amount"))
            if (raw.isZero()) continue
            result += amountOf(asa(id) ?: continue, raw)
        }
        return result
    }

    /** ASA metadata (cached); `null` when the asset is unknown or deleted. */
    private suspend fun asa(assetId: String): Asset? = assetCache.getOrPut(assetId) {
        val params = notFoundAsNull { get("$base/v2/assets/$assetId") }.at("asset", "params")
        val decimals = params.str("decimals")?.toIntOrNull()
        if (params == null || decimals == null) {
            null
        } else {
            tokenAsset(
                chainId = AlgorandChain.ID,
                contract = assetId,
                symbol = params.str("unit-name"),
                name = params.str("name"),
                decimals = decimals,
                priceId = KNOWN_ASAS[assetId] ?: tokenPriceId(AlgorandChain.COINGECKO_PLATFORM, assetId),
            )
        }
    }

    private suspend fun get(
        url: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
        },
    ): JsonElement =
        throttle.run { http.get(url, block).body<JsonElement>() }

    private companion object {
        const val PAGE_SIZE = 100
        const val FIRST_SYNC_PAGES = 2
        const val MAX_PAGES = 20
        const val MAX_ASSETS = 50
        val KNOWN_ASAS = mapOf("31566704" to "usd-coin", "312769" to "tether")
    }
}

/**
 * Net ALGO and ASA changes of [address] in one indexer transaction, including its inner
 * transactions (application calls). The fee is charged to the sender.
 */
internal suspend fun parseAlgorandTransaction(
    tx: JsonElement,
    address: String,
    asaOf: suspend (String) -> Asset?,
): List<ChainTransaction> {
    val id = tx.str("id") ?: return emptyList()
    val time = tx.long("round-time")?.let { Instant.fromEpochSeconds(it) } ?: Clock.System.now()
    val deltas = LinkedHashMap<String, BigInteger>() // "" = ALGO, otherwise asset id
    var fee: BigInteger? = null

    fun add(key: String, value: BigInteger) {
        deltas[key] = (deltas[key] ?: BigInteger.ZERO) + value
    }

    fun visit(t: JsonElement) {
        val sender = t.str("sender")
        if (sender == address) {
            val f = parseBigInteger(t.str("fee"))
            if (!f.isZero()) {
                add("", BigInteger.ZERO - f)
                fee = (fee ?: BigInteger.ZERO) + f
            } else {
                add("", BigInteger.ZERO)
            }
        }
        t.at("payment-transaction")?.let { pay ->
            val amount = parseBigInteger(pay.str("amount"))
            val close = parseBigInteger(pay.str("close-amount"))
            if (sender == address) add("", BigInteger.ZERO - amount - close)
            if (pay.str("receiver") == address) add("", amount)
            if (pay.str("close-remainder-to") == address) add("", close)
        }
        t.at("asset-transfer-transaction")?.let { axfer ->
            val asset = axfer.str("asset-id") ?: return@let
            val amount = parseBigInteger(axfer.str("amount"))
            val close = parseBigInteger(axfer.str("close-amount"))
            // Clawback transfers move funds of `sender` in asset-transfer-transaction, not of the signer.
            val from = axfer.str("sender") ?: sender
            if (from == address) add(asset, BigInteger.ZERO - amount - close)
            if (axfer.str("receiver") == address) add(asset, amount)
            if (axfer.str("close-to") == address) add(asset, close)
        }
        t.arr("inner-txns").forEach { visit(it) }
    }
    visit(tx)

    val result = mutableListOf<ChainTransaction>()
    for ((key, delta) in deltas) {
        if (key.isEmpty()) {
            if (delta.isZero() && fee == null) continue
            result += accountTransaction(AlgorandChain.ID, id, time, AlgorandChain.ALGO, delta, fee)
        } else {
            if (delta.isZero()) continue
            val asset = asaOf(key) ?: continue
            result += accountTransaction(AlgorandChain.ID, id, time, asset, delta, null)
        }
    }
    return result
}
