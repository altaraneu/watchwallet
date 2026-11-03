package com.altaran.watchwallet.chain.xrp

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.Base58Checks
import com.altaran.watchwallet.chain.common.Hex
import com.altaran.watchwallet.chain.common.accountTransaction
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.arr
import com.altaran.watchwallet.chain.common.at
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.long
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.obj
import com.altaran.watchwallet.chain.common.parseBigInteger
import com.altaran.watchwallet.chain.common.parseDecimalUnits
import com.altaran.watchwallet.chain.common.requireBigInteger
import com.altaran.watchwallet.chain.common.requireDecimalUnits
import com.altaran.watchwallet.chain.common.requireList
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.chain.common.tokenAsset
import com.altaran.watchwallet.core.endpoints.BundledEndpoints
import com.altaran.watchwallet.core.endpoints.EndpointProtocol
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.HostHealth
import com.altaran.watchwallet.core.network.Throttle
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object XrpChain {
    val ID = ChainId("xrp")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.XRP,
        name = "XRP Ledger",
        nativeSymbol = "XRP",
        nativeDecimals = 6,
        nativePriceId = "ripple",
        explorerTxUrl = "https://livenet.xrpl.org/transactions/{tx}",
        explorerAddressUrl = "https://livenet.xrpl.org/accounts/{address}",
        color = 0xFF23292F,
    )

    val XRP: Asset = nativeAsset(CHAIN, name = "XRP")

    /** Issued-currency (trust line) amounts are decimals with 15 significant digits. */
    const val TOKEN_DECIMALS = 15
}

/**
 * XRP Ledger account addresses: classic `r…` (Base58Check with the XRP alphabet, version 0) and
 * mainnet X-addresses (`X…`, XLS-5), which are converted to the classic account. Destination
 * tags are irrelevant for watching a balance and dropped.
 */
object XrpAddresses {
    const val ALPHABET = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz"
    private val X_MAINNET = byteArrayOf(0x05, 0x44)

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        val classic = when {
            text.startsWith("r") -> text.takeIf { accountId(it) != null }
            text.startsWith("X") -> fromXAddress(text)
            else -> null
        } ?: return null
        return WatchTarget.Address(ChainFamily.XRP, XrpChain.ID, classic)
    }

    /** 20-byte account id of a classic address, or `null` when invalid. */
    fun accountId(classic: String): ByteArray? {
        if (classic.length !in 25..35) return null
        val payload = Base58Checks.decode(classic, ALPHABET) ?: return null
        if (payload.size != 21 || payload[0] != 0.toByte()) return null
        return payload.copyOfRange(1, 21)
    }

    fun classicAddress(accountId: ByteArray): String = Base58Checks.encode(byteArrayOf(0) + accountId, ALPHABET)

    /** Classic address of a mainnet X-address; `null` for testnet or malformed input. */
    fun fromXAddress(xAddress: String): String? {
        val payload = Base58Checks.decode(xAddress, ALPHABET) ?: return null
        if (payload.size != 31 || !payload.copyOfRange(0, 2).contentEquals(X_MAINNET)) return null
        val flag = payload[22].toInt()
        val tag = payload.copyOfRange(23, 31)
        if (flag !in 0..1 || tag.copyOfRange(4, 8).any { it != 0.toByte() }) return null
        if (flag == 0 && tag.any { it != 0.toByte() }) return null
        return classicAddress(payload.copyOfRange(2, 22))
    }

    /** Encodes a mainnet X-address (used in tests and for display). */
    fun toXAddress(classic: String, tag: Long? = null): String? {
        val id = accountId(classic) ?: return null
        val tagBytes = ByteArray(8)
        if (tag != null) for (i in 0 until 4) tagBytes[i] = ((tag ushr (8 * i)) and 0xFF).toByte()
        return Base58Checks.encode(X_MAINNET + id + byteArrayOf(if (tag != null) 1 else 0) + tagBytes, ALPHABET)
    }
}

/** XRPL JSON-RPC (`{"method":…, "params":[{…}]}`); errors are reported inside `result`. */
internal class XrplClient(
    private val http: HttpClient,
    private val urls: List<String>,
    private val throttle: Throttle,
    private val health: HostHealth = HostHealth(),
) {
    suspend fun resetHostHealth() = health.reset()

    suspend fun call(method: String, params: JsonObject): JsonElement = health.firstAvailable(urls) { url ->
        val response: JsonObject = throttle.run {
            http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("method", method)
                        put("params", buildJsonArray { add(params) })
                    },
                )
            }.body()
        }
        response["result"] ?: throw ChainDataException("XRPL $method: no result")
    }
}

/**
 * XRP Ledger via public rippled/Clio JSON-RPC. The reported XRP balance is the full account
 * balance; the part locked by the reserve is available from [lockedReserve].
 */
class XrpAdapter(
    http: HttpClient,
    rpcUrls: List<String> = BundledEndpoints.urls(XrpChain.ID, EndpointProtocol.XRPL),
) : ChainAdapter {
    private val client =
        XrplClient(
            http,
            rpcUrls,
            Throttle(maxConcurrent = 2, minInterval = 120.milliseconds, maxJitter = 80.milliseconds),
        )

    override val family = ChainFamily.XRP
    override val chains = listOf(XrpChain.CHAIN)

    override suspend fun resetHostHealth() = client.resetHostHealth()

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("XRPL") {
        val account = address(target)
        val info = accountInfo(account)
        if (info == null) {
            ProbeResult(false, emptyList(), listOf(amountOf(XrpChain.XRP, BigInteger.ZERO)), 0)
        } else {
            val balances = listOf(amountOf(XrpChain.XRP, requireBigInteger(info.str("Balance"), "XRPL balance"))) + trustLines(account).amounts
            ProbeResult(true, listOf(XrpChain.ID), balances, transactionCount = null)
        }
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("XRPL") {
        val account = address(target)
        val info = accountInfo(account)
            ?: return@chainCall SyncResult(listOf(amountOf(XrpChain.XRP, BigInteger.ZERO)), emptyList())
        val lines = trustLines(account)
        val balances = listOf(amountOf(XrpChain.XRP, requireBigInteger(info.str("Balance"), "XRPL balance"))) + lines.amounts
        val assets = balances.associateBy { it.asset.contract ?: "" }.mapValues { it.value.asset }

        val transactions = mutableListOf<ChainTransaction>()
        var marker: JsonElement? = null
        var truncated = false
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES
        var page = 0
        paging@ while (true) {
            val result = client.call(
                "account_tx",
                buildJsonObject {
                    put("account", account)
                    put("ledger_index_min", -1)
                    put("ledger_index_max", -1)
                    put("limit", PAGE_SIZE)
                    put("forward", false)
                    marker?.let { put("marker", it) }
                },
            )
            checkError(result)
            for (entry in result.arr("transactions")) {
                val hash = (entry.obj("tx") ?: entry.obj("tx_json")).str("hash") ?: entry.str("hash") ?: continue
                if (hash in knownTransactions) break@paging
                transactions += parseXrpTransaction(entry, account, assets)
            }
            marker = (result as? JsonObject)?.get("marker") ?: break
            if (++page >= maxPages) {
                truncated = true
                break
            }
        }
        val result = SyncResult(balances, transactions, activeChains = listOf(XrpChain.ID), historyTruncated = truncated)
        if (lines.complete) result else result.withIncompleteBalances(XrpChain.ID)
    }

    /**
     * XRP locked by the account reserve: `reserve_base + OwnerCount × reserve_inc` from the
     * validated ledger (`server_state`). Not spendable, but part of the reported balance.
     */
    suspend fun lockedReserve(target: WatchTarget): TokenAmount = chainCall("XRPL") {
        val info = accountInfo(address(target)) ?: return@chainCall TokenAmount.zero(XrpChain.XRP.decimals)
        val ledger = client.call("server_state", JsonObject(emptyMap())).at("state", "validated_ledger")
        val base = parseBigInteger(ledger.str("reserve_base"))
        val increment = parseBigInteger(ledger.str("reserve_inc"))
        val owners = BigInteger.fromLong(info.long("OwnerCount") ?: 0L)
        TokenAmount(base + increment * owners, XrpChain.XRP.decimals)
    }

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("XRPL supports addresses only")

    /** `account_data` or `null` for an unfunded (non-existent) account. */
    private suspend fun accountInfo(account: String): JsonElement? {
        val result = client.call(
            "account_info",
            buildJsonObject {
                put("account", account)
                put("ledger_index", "validated")
            },
        )
        if (result.str("error") == "actNotFound") return null
        checkError(result)
        return result.obj("account_data") ?: throw ChainDataException("XRPL account: malformed answer")
    }

    private class TrustLines(val amounts: List<AssetAmount>, val complete: Boolean)

    private suspend fun trustLines(account: String): TrustLines {
        val lines = mutableListOf<AssetAmount>()
        var marker: JsonElement? = null
        repeat(MAX_LINE_PAGES) {
            val result = client.call(
                "account_lines",
                buildJsonObject {
                    put("account", account)
                    put("ledger_index", "validated")
                    put("limit", 400)
                    marker?.let { put("marker", it) }
                },
            )
            checkError(result)
            for (line in (result as? JsonObject)?.get("lines").requireList("XRPL trust lines")) {
                val currency = line.str("currency") ?: continue
                val issuer = line.str("account") ?: continue
                val raw = requireDecimalUnits(line.str("balance"), XrpChain.TOKEN_DECIMALS, "XRPL trust line")
                if (raw.isZero()) continue
                lines += amountOf(xrplToken(currency, issuer), raw)
            }
            marker = (result as? JsonObject)?.get("marker") ?: return TrustLines(lines, complete = true)
        }
        return TrustLines(lines, complete = false)
    }

    private fun checkError(result: JsonElement) {
        result.str("error")?.let { throw ChainDataException("XRPL error") }
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val FIRST_SYNC_PAGES = 3
        const val MAX_PAGES = 25
        const val MAX_LINE_PAGES = 5
    }
}

/** Human-readable currency code: 3-letter codes as is, 160-bit hex codes decoded when ASCII. */
internal fun currencyCode(currency: String): String {
    if (currency.length != 40) return currency
    val bytes = Hex.decode(currency) ?: return currency
    val text = bytes.takeWhile { it != 0.toByte() }.toByteArray()
    val printable = text.isNotEmpty() && text.all { it in 0x20..0x7E } && bytes.drop(text.size).all { it == 0.toByte() }
    return if (printable) text.decodeToString() else currency.take(8)
}

private val KNOWN_TOKENS = mapOf(
    "524C555344000000000000000000000000000000.rMxCKbEDwqr76QuheSUMdEGf4B9xJ8m5De" to "ripple-usd",
)

/** Trust-line token asset; contract is `<currency>.<issuer>`. */
internal fun xrplToken(currency: String, issuer: String): Asset {
    val contract = "$currency.$issuer"
    val code = currencyCode(currency)
    return tokenAsset(XrpChain.ID, contract, code, "$code ($issuer)", XrpChain.TOKEN_DECIMALS, KNOWN_TOKENS[contract])
}

private const val RIPPLE_EPOCH_OFFSET = 946_684_800L

/**
 * Net balance changes of [account] from a transaction's metadata (`AffectedNodes`): AccountRoot
 * nodes give the XRP delta (fee included), RippleState nodes the trust-line token deltas.
 */
internal fun parseXrpTransaction(
    entry: JsonElement,
    account: String,
    assets: Map<String, Asset>,
): List<ChainTransaction> {
    val tx = entry.obj("tx") ?: entry.obj("tx_json") ?: return emptyList()
    val hash = tx.str("hash") ?: entry.str("hash") ?: return emptyList()
    val meta = entry.obj("meta") ?: return emptyList()
    val time = tx.long("date")?.let { Instant.fromEpochSeconds(it + RIPPLE_EPOCH_OFFSET) }
        ?: entry.str("close_time_iso")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: Clock.System.now()
    val validated = (entry as? JsonObject)?.get("validated")?.let { (it as? JsonPrimitive)?.boolean } ?: true

    var xrpDelta = BigInteger.ZERO
    var touchedXrp = false
    val tokenDeltas = LinkedHashMap<String, BigInteger>()
    for (wrapper in meta.arr("AffectedNodes")) {
        val (kind, node) = (wrapper as? JsonObject)?.entries?.firstOrNull() ?: continue
        val final = node.obj("FinalFields") ?: node.obj("NewFields") ?: continue
        val previous = node.obj("PreviousFields")
        when (node.str("LedgerEntryType")) {
            "AccountRoot" -> {
                if (final.str("Account") != account) continue
                val after = if (kind == "DeletedNode") BigInteger.ZERO else parseBigInteger(final.str("Balance"))
                val before = when {
                    previous?.str("Balance") != null -> parseBigInteger(previous.str("Balance"))
                    kind == "CreatedNode" -> BigInteger.ZERO
                    kind == "DeletedNode" -> parseBigInteger(final.str("Balance"))
                    else -> after
                }
                xrpDelta += after - before
                touchedXrp = true
            }

            "RippleState" -> {
                val low = final.at("LowLimit").str("issuer")
                val high = final.at("HighLimit").str("issuer")
                if (account != low && account != high) continue
                val currency = final.at("Balance").str("currency") ?: continue
                val after = if (kind ==
                    "DeletedNode"
                ) {
                    BigInteger.ZERO
                } else {
                    parseDecimalUnits(final.at("Balance").str("value"), XrpChain.TOKEN_DECIMALS)
                }
                val before =
                    previous?.at("Balance")?.str("value")?.let { parseDecimalUnits(it, XrpChain.TOKEN_DECIMALS) }
                        ?: if (kind == "CreatedNode") BigInteger.ZERO else after
                // RippleState balances are from the low account's perspective.
                val change = if (account == low) after - before else before - after
                val issuer = if (account == low) high else low
                val contract = "$currency.$issuer"
                tokenDeltas[contract] = (tokenDeltas[contract] ?: BigInteger.ZERO) + change
            }
        }
    }
    val result = mutableListOf<ChainTransaction>()
    val feePayer = tx.str("Account") == account
    if (touchedXrp && (!xrpDelta.isZero() || feePayer)) {
        val fee = if (feePayer) parseBigInteger(tx.str("Fee")) else null
        result += accountTransaction(XrpChain.ID, hash, time, XrpChain.XRP, xrpDelta, fee, validated)
    }
    tokenDeltas.filterValues { !it.isZero() }.forEach { (contract, delta) ->
        val asset = assets[contract] ?: xrplToken(contract.substringBefore('.'), contract.substringAfter('.'))
        result += accountTransaction(XrpChain.ID, hash, time, asset, delta, null, validated)
    }
    return result
}
