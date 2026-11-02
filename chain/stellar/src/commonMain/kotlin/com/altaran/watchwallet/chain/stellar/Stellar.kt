package com.altaran.watchwallet.chain.stellar

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.Base32
import com.altaran.watchwallet.chain.common.Crc16
import com.altaran.watchwallet.chain.common.accountTransaction
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.arr
import com.altaran.watchwallet.chain.common.at
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.long
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.notFoundAsNull
import com.altaran.watchwallet.chain.common.parseBigInteger
import com.altaran.watchwallet.chain.common.parseDecimalUnits
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.chain.common.tokenAsset
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object StellarChain {
    val ID = ChainId("stellar")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.STELLAR,
        name = "Stellar",
        nativeSymbol = "XLM",
        nativeDecimals = 7,
        nativePriceId = "stellar",
        explorerTxUrl = "https://stellar.expert/explorer/public/tx/{tx}",
        explorerAddressUrl = "https://stellar.expert/explorer/public/account/{address}",
        color = 0xFF7D00FF,
    )

    val XLM: Asset = nativeAsset(CHAIN, name = "Stellar Lumens")

    const val DEFAULT_HORIZON_URL = "https://horizon.stellar.org"

    /** Base reserve per ledger entry in stroops (0.5 XLM, protocol constant since 2019). */
    const val BASE_RESERVE_STROOPS = 5_000_000L
}

/**
 * Stellar account ids (strkey `G…`: base32 of version byte, 32-byte ed25519 key and CRC16-XModem)
 * and muxed accounts (`M…`, SEP-23), which are converted to their underlying `G…` account.
 * Secret seeds (`S…`) have a different version byte and are never accepted.
 */
object StellarAddresses {
    private const val VERSION_ACCOUNT = 6 shl 3
    private const val VERSION_MUXED = 12 shl 3

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        val account = when {
            text.length == 56 && text.startsWith("G") -> decode(text, VERSION_ACCOUNT, 32)?.let { text }

            text.length == 69 && text.startsWith("M") -> decode(text, VERSION_MUXED, 40)?.let {
                encode(VERSION_ACCOUNT, it.copyOfRange(0, 32))
            }

            else -> null
        } ?: return null
        return WatchTarget.Address(ChainFamily.STELLAR, StellarChain.ID, account)
    }

    /** Payload of a strkey with the given version byte and payload size, or `null`. */
    internal fun decode(text: String, version: Int, size: Int): ByteArray? {
        val raw = Base32.decode(text) ?: return null
        if (raw.size != size + 3 || (raw[0].toInt() and 0xFF) != version) return null
        val body = raw.copyOfRange(0, size + 1)
        val crc = Crc16.xmodem(body)
        val stored = (raw[size + 1].toInt() and 0xFF) or ((raw[size + 2].toInt() and 0xFF) shl 8)
        return if (crc == stored) body.copyOfRange(1, size + 1) else null
    }

    internal fun encode(version: Int, payload: ByteArray): String {
        val body = byteArrayOf(version.toByte()) + payload
        val crc = Crc16.xmodem(body)
        return Base32.encode(body + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte()))
    }

    /** Muxed `M…` address for an account and a 64-bit id (used in tests and for display). */
    fun toMuxed(account: String, id: Long): String? {
        val key = decode(account, VERSION_ACCOUNT, 32) ?: return null
        val idBytes = ByteArray(8) { ((id ushr (8 * (7 - it))) and 0xFF).toByte() }
        return encode(VERSION_MUXED, key + idBytes)
    }
}

/**
 * Stellar via Horizon: XLM and trustline balances, payment history (payments, account creation,
 * path payments) and fees from the account's transactions. Account merges are not reflected in
 * history (Horizon reports their amount only as an effect). The reported XLM balance is the full
 * balance; the part locked by the minimum balance is available from [lockedReserve].
 */
class StellarAdapter(
    private val http: HttpClient,
    horizonUrl: String = StellarChain.DEFAULT_HORIZON_URL,
) : ChainAdapter {
    private val base = horizonUrl.trimEnd('/')
    private val throttle = Throttle(maxConcurrent = 3, minInterval = 100.milliseconds, maxJitter = 80.milliseconds)

    override val family = ChainFamily.STELLAR
    override val chains = listOf(StellarChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("Horizon") {
        val account = account(address(target))
        if (account == null) {
            ProbeResult(false, emptyList(), listOf(amountOf(StellarChain.XLM, BigInteger.ZERO)), 0)
        } else {
            ProbeResult(true, listOf(StellarChain.ID), balances(account), transactionCount = null)
        }
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("Horizon") {
        val address = address(target)
        val account = account(address)
            ?: return@chainCall SyncResult(listOf(amountOf(StellarChain.XLM, BigInteger.ZERO)), emptyList())
        val balances = balances(account)
        val assets = balances.associate { (it.asset.contract ?: "native") to it.asset }
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES

        val payments = records("$base/accounts/$address/payments", maxPages) {
            it.str("transaction_hash") in
                knownTransactions
        }
        val txs = records("$base/accounts/$address/transactions", maxPages) { it.str("hash") in knownTransactions }

        val sums = LinkedHashMap<Pair<String, String>, Entry>()
        fun add(
            hash: String,
            assetKey: String,
            asset: Asset,
            delta: BigInteger,
            time: Instant,
            fee: BigInteger? = null,
        ) {
            val key = hash to assetKey
            val current = sums[key] ?: Entry(asset, BigInteger.ZERO, null, time)
            sums[key] = current.copy(delta = current.delta + delta, fee = fee ?: current.fee)
        }
        for (tx in txs.items) {
            val hash = tx.str("hash") ?: continue
            val payer = tx.str("fee_account") ?: tx.str("source_account")
            if (payer != address) continue
            val fee = parseBigInteger(tx.str("fee_charged"))
            add(hash, "native", StellarChain.XLM, -fee, time(tx), fee)
        }
        for (payment in payments.items) {
            if (payment.bool("transaction_successful") == false) continue
            val hash = payment.str("transaction_hash") ?: continue
            for ((assetKey, asset, delta) in paymentDeltas(
                payment,
                address,
                assets,
            )) {
                add(hash, assetKey, asset, delta, time(payment))
            }
        }
        val transactions: List<ChainTransaction> = sums.map { (key, entry) ->
            accountTransaction(StellarChain.ID, key.first, entry.time, entry.asset, entry.delta, entry.fee)
        }
        SyncResult(
            balances,
            transactions,
            activeChains = listOf(StellarChain.ID),
            historyTruncated =
                payments.truncated || txs.truncated,
        )
    }

    /**
     * XLM locked by the minimum balance: `(2 + subentry_count + num_sponsoring − num_sponsored) ×
     * base reserve (0.5 XLM)`. Not spendable, but part of the reported balance.
     */
    suspend fun lockedReserve(target: WatchTarget): TokenAmount = chainCall("Horizon") {
        val account = account(address(target)) ?: return@chainCall TokenAmount.zero(StellarChain.XLM.decimals)
        val entries =
            2 + (account.long("subentry_count") ?: 0L) + (account.long("num_sponsoring") ?: 0L) -
                (account.long("num_sponsored") ?: 0L)
        TokenAmount(BigInteger.fromLong(entries * StellarChain.BASE_RESERVE_STROOPS), StellarChain.XLM.decimals)
    }

    private data class Entry(val asset: Asset, val delta: BigInteger, val fee: BigInteger?, val time: Instant)

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("Stellar supports addresses only")

    private suspend fun account(address: String): JsonElement? = notFoundAsNull { get("$base/accounts/$address") }

    private fun balances(account: JsonElement): List<AssetAmount> {
        val result = mutableListOf<AssetAmount>()
        var native = BigInteger.ZERO
        for (entry in account.arr("balances")) {
            when (entry.str("asset_type")) {
                "native" -> {
                    native = parseDecimalUnits(entry.str("balance"), StellarChain.XLM.decimals)
                }

                "credit_alphanum4", "credit_alphanum12" -> {
                    val code = entry.str("asset_code") ?: continue
                    val issuer = entry.str("asset_issuer") ?: continue
                    val raw = parseDecimalUnits(entry.str("balance"), StellarChain.XLM.decimals)
                    if (raw.isZero()) continue
                    result += amountOf(stellarAsset(code, issuer), raw)
                }
            }
        }
        return listOf(amountOf(StellarChain.XLM, native)) + result
    }

    private class Records(val items: List<JsonElement>, val truncated: Boolean)

    private suspend fun records(url: String, maxPages: Int, isKnown: (JsonElement) -> Boolean): Records {
        val items = mutableListOf<JsonElement>()
        var cursor: String? = null
        repeat(maxPages) {
            val page = get(url) {
                parameter("order", "desc")
                parameter("limit", PAGE_SIZE)
                cursor?.let { parameter("cursor", it) }
            }
            val records = page.at("_embedded").arr("records")
            for (record in records) {
                if (isKnown(record)) return Records(items, truncated = false)
                items += record
            }
            if (records.size < PAGE_SIZE) return Records(items, truncated = false)
            cursor = records.last().str("paging_token") ?: return Records(items, truncated = true)
        }
        return Records(items, truncated = true)
    }

    private suspend fun get(
        url: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
        },
    ): JsonElement =
        throttle.run { http.get(url, block).body<JsonElement>() }

    private companion object {
        const val PAGE_SIZE = 200
        const val FIRST_SYNC_PAGES = 2
        const val MAX_PAGES = 20
    }
}

/** Trustline asset; contract id is `CODE-ISSUER` (asset ids must not contain colons). */
internal fun stellarAsset(code: String, issuer: String): Asset =
    tokenAsset(
        StellarChain.ID,
        "$code-$issuer",
        code,
        "$code ($issuer)",
        StellarChain.XLM.decimals,
        KNOWN_ASSETS["$code-$issuer"],
    )

private val KNOWN_ASSETS = mapOf(
    "USDC-GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN" to "usd-coin",
    "EURC-GDHU6WRG4IEQXM5NZ4BMPKOXHW76MZM4Y2IEMFDVXBSDP6SJY4ITNPP2" to "euro-coin",
)

private fun JsonElement?.bool(key: String): Boolean? = (at(key) as? JsonPrimitive)?.booleanOrNull

private fun time(record: JsonElement): Instant =
    record.str("created_at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Clock.System.now()

private fun assetKeyOf(type: String?, code: String?, issuer: String?): String? = when (type) {
    "native" -> "native"
    "credit_alphanum4", "credit_alphanum12" -> if (code != null && issuer != null) "$code-$issuer" else null
    else -> null
}

/** Per-asset deltas of one Horizon payment record for [address] (fees are handled separately). */
internal fun paymentDeltas(
    payment: JsonElement,
    address: String,
    assets: Map<String, Asset>,
): List<Triple<String, Asset, BigInteger>> {
    fun asset(key: String): Asset =
        if (key ==
            "native"
        ) {
            StellarChain.XLM
        } else {
            assets[key]
                ?: stellarAsset(key.substringBefore('-'), key.substringAfter('-'))
        }
    val decimals = StellarChain.XLM.decimals
    val result = mutableListOf<Triple<String, Asset, BigInteger>>()
    when (payment.str("type")) {
        "create_account" -> {
            val amount = parseDecimalUnits(payment.str("starting_balance"), decimals)
            if (payment.str("account") == address) result += Triple("native", StellarChain.XLM, amount)
            if (payment.str("funder") == address) result += Triple("native", StellarChain.XLM, -amount)
        }

        "payment", "path_payment_strict_send", "path_payment_strict_receive" -> {
            val from = payment.str("from")
            val to = payment.str("to")
            val receivedKey =
                assetKeyOf(payment.str("asset_type"), payment.str("asset_code"), payment.str("asset_issuer"))
            val sentKey = if (payment.str("type") == "payment") {
                receivedKey
            } else {
                assetKeyOf(
                    payment.str("source_asset_type"),
                    payment.str("source_asset_code"),
                    payment.str("source_asset_issuer"),
                )
            }
            val received = parseDecimalUnits(payment.str("amount"), decimals)
            val sent = if (payment.str("type") ==
                "payment"
            ) {
                received
            } else {
                parseDecimalUnits(payment.str("source_amount"), decimals)
            }
            if (to == address && receivedKey != null) result += Triple(receivedKey, asset(receivedKey), received)
            if (from == address && sentKey != null) result += Triple(sentKey, asset(sentKey), -sent)
        }
    }
    return result
}
