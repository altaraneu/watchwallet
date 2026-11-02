package com.altaran.watchwallet.chain.ton

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.Base64Codec
import com.altaran.watchwallet.chain.common.Crc16
import com.altaran.watchwallet.chain.common.Hex
import com.altaran.watchwallet.chain.common.accountTransaction
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.arr
import com.altaran.watchwallet.chain.common.at
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.content
import com.altaran.watchwallet.chain.common.long
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.notFoundAsNull
import com.altaran.watchwallet.chain.common.obj
import com.altaran.watchwallet.chain.common.parseBigInteger
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.chain.common.tokenAsset
import com.altaran.watchwallet.chain.common.tokenPriceId
import com.altaran.watchwallet.chain.common.withRateLimitBackoff
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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object TonChain {
    val ID = ChainId("ton")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.TON,
        name = "TON",
        nativeSymbol = "TON",
        nativeDecimals = 9,
        nativePriceId = "the-open-network",
        explorerTxUrl = "https://tonviewer.com/transaction/{tx}",
        explorerAddressUrl = "https://tonviewer.com/{address}",
        color = 0xFF0098EA,
    )

    val TON: Asset = nativeAsset(CHAIN, name = "Toncoin")

    const val COINGECKO_PLATFORM = "the-open-network"

    /** Jettons without a `decimals` field in their metadata use 9 (TEP-64 default). */
    const val DEFAULT_JETTON_DECIMALS = 9
}

/**
 * TON addresses: user-friendly (36 bytes base64/base64url: flag, workchain, 32-byte hash,
 * CRC16-XModem; `EQ…`/`UQ…` on mainnet) and raw `workchain:hex`. Testnet-only addresses
 * (`kQ…`/`0Q…`) are rejected. The input form is kept for display; [toRaw] is used for matching.
 */
object TonAddresses {
    private const val BOUNCEABLE = 0x11
    private const val NON_BOUNCEABLE = 0x51
    private const val TESTNET = 0x80
    private val RAW = Regex("""^(-1|0):([0-9a-fA-F]{64})$""")

    class Account(val workchain: Int, val hash: ByteArray) {
        /** Raw form `0:ABCD…` with upper-case hex, as toncenter v3 reports addresses. */
        val raw: String get() = "$workchain:${Hex.encode(hash).uppercase()}"
    }

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        decode(text) ?: return null
        val stored = if (RAW.matches(text)) text.lowercase() else text
        return WatchTarget.Address(ChainFamily.TON, TonChain.ID, stored)
    }

    fun decode(text: String): Account? {
        RAW.matchEntire(text)?.let { match ->
            return Account(match.groupValues[1].toInt(), Hex.decode(match.groupValues[2])!!)
        }
        if (text.length != 48) return null
        val bytes = Base64Codec.decode(text) ?: return null
        if (bytes.size != 36) return null
        val flag = bytes[0].toInt() and 0xFF
        if (flag and TESTNET != 0) return null
        if (flag != BOUNCEABLE && flag != NON_BOUNCEABLE) return null
        val workchain = bytes[1].toInt() // signed: 0x00 basechain, 0xFF masterchain
        if (workchain != 0 && workchain != -1) return null
        val crc = Crc16.xmodem(bytes.copyOfRange(0, 34))
        if ((bytes[34].toInt() and 0xFF) != crc shr 8 || (bytes[35].toInt() and 0xFF) != crc and 0xFF) return null
        return Account(workchain, bytes.copyOfRange(2, 34))
    }

    /** Raw upper-case form of any valid address, or `null`. */
    fun toRaw(address: String): String? = decode(address.trim())?.raw

    /** User-friendly URL-safe form (`EQ…` bounceable / `UQ…` non-bounceable). */
    fun toFriendly(address: String, bounceable: Boolean = false): String? {
        val account = decode(address.trim()) ?: return null
        val body =
            byteArrayOf((if (bounceable) BOUNCEABLE else NON_BOUNCEABLE).toByte(), account.workchain.toByte()) +
                account.hash
        val crc = Crc16.xmodem(body)
        return Base64Codec.encode(body + byteArrayOf((crc shr 8).toByte(), crc.toByte()), urlSafe = true)
    }
}

internal object TonJettons {
    data class Info(val symbol: String, val name: String, val decimals: Int, val coingeckoId: String?)

    /** Keyed by raw master address. */
    val KNOWN: Map<String, Info> = mapOf(
        "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs" to Info("USDT", "Tether USD", 6, "tether"),
        "EQAvlWFDxGF2lXm67y4yzC17wYKD9A0guwPkMs1gOsM__NOT" to Info("NOT", "Notcoin", 9, "notcoin"),
        "EQCvxJy4eG8hyHBFsZ7eePxrRsUQSFE_jpptRAYBmcG_DOGS" to Info("DOGS", "Dogs", 9, "dogs-2"),
    ).mapNotNull { (address, info) -> TonAddresses.toRaw(address)?.let { it to info } }.toMap()
}

/**
 * TON via toncenter API v3 (no key: about one request per second): balance, jetton balances,
 * TON history (exact deltas from account state before/after, fees included) and jetton
 * transfers.
 */
class TonAdapter(
    private val http: HttpClient,
    apiUrl: String = BundledEndpoints.first(TonChain.ID, EndpointProtocol.TONCENTER),
    minInterval: Duration = 1_100.milliseconds,
) : ChainAdapter {
    private val base = apiUrl.trimEnd('/')
    private val throttle = Throttle(maxConcurrent = 1, minInterval = minInterval, maxJitter = 100.milliseconds)

    override val family = ChainFamily.TON
    override val chains = listOf(TonChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("toncenter") {
        val address = address(target)
        val account = account(address)
        val active = account != null && account.str("status") != "nonexist"
        val balances =
            listOf(amountOf(TonChain.TON, account.str("balance"))) + if (active) jettons(address).first else emptyList()
        ProbeResult(active, if (active) listOf(TonChain.ID) else emptyList(), balances, transactionCount = null)
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall(
        "toncenter",
    ) {
        val address = address(target)
        val raw = TonAddresses.toRaw(address) ?: throw ChainDataException("Invalid TON address")
        val account = account(address)
        if (account == null || account.str("status") == "nonexist") {
            return@chainCall SyncResult(listOf(amountOf(TonChain.TON, BigInteger.ZERO)), emptyList())
        }
        val (jettonBalances, jettonAssets) = jettons(address)
        val balances = listOf(amountOf(TonChain.TON, account.str("balance"))) + jettonBalances
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES

        val txPages = pages("$base/transactions", "transactions", maxPages, { parameter("account", address) }) {
            hashHex(it.str("hash")) in knownTransactions
        }
        val transferPages =
            pages("$base/jetton/transfers", "jetton_transfers", maxPages, { parameter("owner_address", address) }) {
                hashHex(it.str("transaction_hash")) in knownTransactions
            }
        val transactions = txPages.items.mapNotNull { parseTonTransaction(it) } +
            transferPages.items.mapNotNull { parseJettonTransfer(it, raw, jettonAssets) }
        SyncResult(
            balances,
            transactions,
            activeChains = listOf(TonChain.ID),
            historyTruncated =
                txPages.truncated || transferPages.truncated,
        )
    }

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("TON supports addresses only")

    private suspend fun account(address: String): JsonElement? = notFoundAsNull {
        get("$base/account") { parameter("address", address) }
    }

    /** Jetton balances plus the asset of every master seen (also zero balances, for history). */
    private suspend fun jettons(address: String): Pair<List<AssetAmount>, Map<String, Asset>> {
        val response = get("$base/jetton/wallets") {
            parameter("owner_address", address)
            parameter("limit", JETTON_LIMIT)
        }
        val metadata = response.obj("metadata")
        val assets = LinkedHashMap<String, Asset>()
        val balances = mutableListOf<AssetAmount>()
        for (wallet in response.arr("jetton_wallets")) {
            val master = wallet.str("jetton")?.let { TonAddresses.toRaw(it) } ?: continue
            val asset = jettonAsset(master, metadata)
            assets[master] = asset
            val raw = parseBigInteger(wallet.str("balance"))
            if (!raw.isZero()) balances += amountOf(asset, raw)
        }
        return balances to assets
    }

    private class Paged(val items: List<JsonElement>, val truncated: Boolean)

    private suspend fun pages(
        url: String,
        field: String,
        maxPages: Int,
        filter: HttpRequestBuilder.() -> Unit,
        isKnown: (JsonElement) -> Boolean,
    ): Paged {
        val items = mutableListOf<JsonElement>()
        for (page in 0 until maxPages) {
            val response = get(url) {
                filter()
                parameter("limit", PAGE_SIZE)
                parameter("offset", page * PAGE_SIZE)
                parameter("sort", "desc")
            }
            val list = response.arr(field)
            for (item in list) {
                if (isKnown(item)) return Paged(items, truncated = false)
                items += item
            }
            if (list.size < PAGE_SIZE) return Paged(items, truncated = false)
        }
        return Paged(items, truncated = true)
    }

    private suspend fun get(url: String, block: HttpRequestBuilder.() -> Unit): JsonElement =
        withRateLimitBackoff(RATE_LIMIT_RETRIES) { throttle.run { http.get(url, block).body<JsonElement>() } }

    private companion object {
        const val PAGE_SIZE = 50
        const val FIRST_SYNC_PAGES = 2
        const val MAX_PAGES = 20
        const val JETTON_LIMIT = 100
        const val RATE_LIMIT_RETRIES = 3
    }
}

/** Jetton asset from the built-in list or toncenter's `metadata` map (keyed by raw address). */
internal fun jettonAsset(masterRaw: String, metadata: JsonElement?): Asset {
    val known = TonJettons.KNOWN[masterRaw]
    val info = metadata?.let { m ->
        (m as? JsonObject)?.entries?.firstOrNull { TonAddresses.toRaw(it.key) == masterRaw }?.value
    }
        ?.arr("token_info")?.firstOrNull()
    val decimals = known?.decimals
        ?: info.str("decimals")?.toIntOrNull()
        ?: info.at("extra", "decimals").content?.toIntOrNull()
        ?: TonChain.DEFAULT_JETTON_DECIMALS
    return tokenAsset(
        chainId = TonChain.ID,
        contract = masterRaw,
        symbol = known?.symbol ?: info.str("symbol"),
        name = known?.name ?: info.str("name"),
        decimals = decimals,
        priceId =
            known?.coingeckoId
                ?: TonAddresses.toFriendly(masterRaw, bounceable = true)?.let {
                    tokenPriceId(TonChain.COINGECKO_PLATFORM, it)
                },
    )
}

/** Base64 transaction hash → lower-case hex (the form explorers link to). */
internal fun hashHex(hash: String?): String? = hash?.let { Base64Codec.decode(it)?.let(Hex::encode) ?: it }

/**
 * TON delta of one transaction of the account: balance after − balance before when toncenter
 * provides account states, otherwise incoming value − outgoing values − total fees.
 */
internal fun parseTonTransaction(tx: JsonElement): ChainTransaction? {
    val hash = hashHex(tx.str("hash")) ?: return null
    val time = tx.long("now")?.let { Instant.fromEpochSeconds(it) } ?: Clock.System.now()
    val totalFees = parseBigInteger(tx.str("total_fees"))
    val before = tx.at("account_state_before", "balance").content
    val after = tx.at("account_state_after", "balance").content
    val delta = if (before != null && after != null) {
        parseBigInteger(after) - parseBigInteger(before)
    } else {
        val incoming = parseBigInteger(tx.at("in_msg", "value").content)
        val outgoing = tx.arr("out_msgs").fold(BigInteger.ZERO) { sum, msg -> sum + parseBigInteger(msg.str("value")) }
        incoming - outgoing - totalFees
    }
    // An external inbound message means the wallet itself signed (and paid for) the transaction.
    val initiated = tx.obj("in_msg")?.get("source").let { it == null || it.content == null }
    if (delta.isZero() && !initiated) return null
    return accountTransaction(TonChain.ID, hash, time, TonChain.TON, delta, if (initiated) totalFees else null)
}

internal fun parseJettonTransfer(
    transfer: JsonElement,
    ownerRaw: String,
    assets: Map<String, Asset>,
): ChainTransaction? {
    if ((transfer as? JsonObject)?.get("transaction_aborted")?.let { (it as? JsonPrimitive)?.booleanOrNull } ==
        true
    ) {
        return null
    }
    val hash = hashHex(transfer.str("transaction_hash")) ?: return null
    val master = transfer.str("jetton_master")?.let { TonAddresses.toRaw(it) } ?: return null
    val asset = assets[master] ?: jettonAsset(master, null)
    val amount = parseBigInteger(transfer.str("amount"))
    if (amount.isZero()) return null
    val from = transfer.str("source")?.let { TonAddresses.toRaw(it) }
    val to = transfer.str("destination")?.let { TonAddresses.toRaw(it) }
    val delta = when {
        from == ownerRaw && to == ownerRaw -> BigInteger.ZERO
        to == ownerRaw -> amount
        from == ownerRaw -> -amount
        else -> return null
    }
    val time = transfer.long("transaction_now")?.let { Instant.fromEpochSeconds(it) } ?: Clock.System.now()
    return accountTransaction(TonChain.ID, hash, time, asset, delta, null, isSelfTransfer = from == to)
}
