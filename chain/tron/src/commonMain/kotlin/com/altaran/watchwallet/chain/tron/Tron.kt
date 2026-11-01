package com.altaran.watchwallet.chain.tron

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
import com.altaran.watchwallet.chain.common.content
import com.altaran.watchwallet.chain.common.long
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.notFoundAsNull
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
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object TronChain {
    val ID = ChainId("tron")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.TRON,
        name = "Tron",
        nativeSymbol = "TRX",
        nativeDecimals = 6,
        nativePriceId = "tron",
        explorerTxUrl = "https://tronscan.org/#/transaction/{tx}",
        explorerAddressUrl = "https://tronscan.org/#/address/{address}",
        color = 0xFFEB0029,
    )

    val TRX: Asset = nativeAsset(CHAIN, name = "TRON")

    const val DEFAULT_API_URL = "https://api.trongrid.io"
    const val COINGECKO_PLATFORM = "tron"
    const val USDT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
}

/** Tron addresses: Base58Check of 21 bytes starting with 0x41 (`T…`). */
object TronAddresses {
    private const val PREFIX: Byte = 0x41

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        if (text.length != 34 || !text.startsWith("T")) return null
        val payload = Base58Checks.decode(text) ?: return null
        if (payload.size != 21 || payload[0] != PREFIX) return null
        return WatchTarget.Address(ChainFamily.TRON, TronChain.ID, text)
    }

    /** `41…` hex (as used in raw transactions) to Base58 `T…`; `null` when malformed. */
    fun fromHex(hex: String): String? {
        val bytes = Hex.decode(hex) ?: return null
        if (bytes.size != 21 || bytes[0] != PREFIX) return null
        return Base58Checks.encode(bytes)
    }
}

/** Major TRC-20 tokens: symbol, name, decimals, CoinGecko id. */
internal object TronTokens {
    data class Info(val symbol: String, val name: String, val decimals: Int, val coingeckoId: String?)

    val KNOWN: Map<String, Info> = mapOf(
        TronChain.USDT to Info("USDT", "Tether USD", 6, "tether"),
        "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8" to Info("USDC", "USD Coin", 6, "usd-coin"),
        "TPYmHEhy5n8TCEfYGqW2rPxsghSfzghPDn" to Info("USDD", "Decentralized USD", 18, "usdd"),
        "TNUC9Qb1rRpS5CbWLmNMxXBjyFoydXjWFR" to Info("WTRX", "Wrapped TRX", 6, "wrapped-tron"),
        "TAFjULxiVgT4qWk6UZwjqwZXTSaGaqnVp4" to Info("BTT", "BitTorrent", 18, "bittorrent"),
        "TCFLL5dx5ZJdKnWuesXxi1VPwjLVmWZZy9" to Info("JST", "JUST", 18, "just"),
        "TSSMHYeV2uE9qYH95DqyoCuNCzEL1NvU3S" to Info("SUN", "SUN", 18, "sun-token"),
        "TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7" to Info("WIN", "WINkLink", 6, "wink"),
        "TUpMhErZL2fhh4sVNULAbNKLokS4GjC1F4" to Info("TUSD", "TrueUSD", 18, "true-usd"),
    )

    private val KNOWN_SYMBOLS = KNOWN.values.map { it.symbol.uppercase() }.toSet() + setOf("TRX", "USDC", "USDT")

    /** A token that copies the symbol of a major token from another contract (typical airdrop spam). */
    fun isImpersonation(contract: String, symbol: String?): Boolean =
        contract !in KNOWN && symbol?.trim()?.uppercase() in KNOWN_SYMBOLS
}

/**
 * Tron via TronGrid REST (no API key): TRX (liquid + staked v2), TRC-10 and TRC-20 balances,
 * TRX history and TRC-20 transfers. Burned fees are part of the TRX delta of the fee payer.
 *
 * TRC-20 tokens without known metadata are only listed when their decimals were seen in the
 * account's transfer history; transfers of unknown tokens with no balance (address-poisoning
 * spam) are dropped.
 */
class TronAdapter(
    private val http: HttpClient,
    apiUrl: String = TronChain.DEFAULT_API_URL,
) : ChainAdapter {
    private val base = apiUrl.trimEnd('/')

    // TronGrid without a key: a few requests per second per IP.
    private val throttle = Throttle(maxConcurrent = 2, minInterval = 250.milliseconds, maxJitter = 100.milliseconds)

    override val family = ChainFamily.TRON
    override val chains = listOf(TronChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("TronGrid") {
        val account = account(address(target))
        val balances = account?.let { balances(it, emptyMap()) } ?: listOf(amountOf(TronChain.TRX, BigInteger.ZERO))
        ProbeResult(
            account != null,
            if (account !=
                null
            ) {
                listOf(TronChain.ID)
            } else {
                emptyList()
            },
            balances,
            transactionCount = null,
        )
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("TronGrid") {
        val address = address(target)
        val account = account(address)
            ?: return@chainCall SyncResult(listOf(amountOf(TronChain.TRX, BigInteger.ZERO)), emptyList())
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES

        val trc20 = pages("$base/v1/accounts/$address/transactions/trc20", maxPages) {
            it.str("transaction_id") in
                knownTransactions
        }
        val seenTokens = trc20.items.mapNotNull { item ->
            val info = item.at("token_info") ?: return@mapNotNull null
            val contract = info.str("address") ?: return@mapNotNull null
            val decimals = info.str("decimals")?.toIntOrNull() ?: return@mapNotNull null
            if (TronTokens.isImpersonation(contract, info.str("symbol"))) return@mapNotNull null
            contract to trc20Asset(contract, info.str("symbol"), info.str("name"), decimals)
        }.toMap()
        val balances = balances(account, seenTokens)
        val heldContracts = balances.mapNotNull { it.asset.contract }.toSet()

        val trx = pages("$base/v1/accounts/$address/transactions", maxPages) { it.str("txID") in knownTransactions }
        val transactions = trx.items.mapNotNull { parseTrxTransaction(it, address) } +
            trc20.items.mapNotNull { item ->
                val contract = item.at("token_info").str("address") ?: return@mapNotNull null
                val asset = seenTokens[contract]?.takeIf { contract in heldContracts || contract in TronTokens.KNOWN }
                    ?: return@mapNotNull null
                parseTrc20Transfer(item, address, asset)
            }
        SyncResult(
            balances,
            transactions,
            activeChains = listOf(TronChain.ID),
            historyTruncated =
                trc20.truncated || trx.truncated,
        )
    }

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("Tron supports addresses only")

    /** Account object, or `null` when the address was never activated. */
    private suspend fun account(address: String): JsonElement? =
        get("$base/v1/accounts/$address").arr("data").firstOrNull()

    private suspend fun balances(account: JsonElement, seenTokens: Map<String, Asset>): List<AssetAmount> {
        // Liquid TRX plus TRX staked for resources (Stake 2.0) and TRX waiting to be unstaked.
        var trx = parseBigInteger(account.str("balance"))
        account.arr("frozenV2").forEach { trx += parseBigInteger(it.str("amount")) }
        account.arr("unfrozenV2").forEach { trx += parseBigInteger(it.str("unfreeze_amount")) }
        val result = mutableListOf(amountOf(TronChain.TRX, trx))

        for (entry in account.arr("trc20")) {
            val (contract, value) = (entry as? JsonObject)?.entries?.firstOrNull() ?: continue
            val raw = parseBigInteger(value.content)
            if (raw.isZero()) continue
            val known = TronTokens.KNOWN[contract]
            val asset = when {
                known != null -> trc20Asset(contract, known.symbol, known.name, known.decimals)
                else -> seenTokens[contract] ?: continue
            }
            result += amountOf(asset, raw)
        }
        for (entry in account.arr("assetV2").take(MAX_TRC10)) {
            val id = entry.str("key") ?: continue
            val raw = parseBigInteger(entry.str("value"))
            if (raw.isZero()) continue
            val asset = trc10Asset(id) ?: continue
            result += amountOf(asset, raw)
        }
        return result
    }

    private suspend fun trc10Asset(id: String): Asset? {
        // A failed lookup fails the sync (stored balances are kept) instead of dropping the token.
        val info = notFoundAsNull { get("$base/wallet/getassetissuebyid?value=$id") } ?: return null
        val precision = info.str("precision")?.toIntOrNull() ?: 0
        val name = info.str("name")?.let { hexText(it) }
        val symbol = info.str("abbr")?.let { hexText(it) } ?: name
        return tokenAsset(TronChain.ID, "trc10-$id", symbol, name, precision)
    }

    private class Paged(val items: List<JsonElement>, val truncated: Boolean)

    private suspend fun pages(url: String, maxPages: Int, isKnown: (JsonElement) -> Boolean): Paged {
        val items = mutableListOf<JsonElement>()
        var fingerprint: String? = null
        repeat(maxPages) {
            val page = get(url) {
                parameter("limit", PAGE_SIZE)
                fingerprint?.let { parameter("fingerprint", it) }
            }
            for (item in page.arr("data")) {
                if (isKnown(item)) return Paged(items, truncated = false)
                items += item
            }
            fingerprint = page.at("meta", "fingerprint").content ?: return Paged(items, truncated = false)
        }
        return Paged(items, truncated = true)
    }

    private suspend fun get(
        url: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
        },
    ): JsonElement =
        withRateLimitBackoff(RATE_LIMIT_RETRIES) { throttle.run { http.get(url, block).body<JsonElement>() } }

    private companion object {
        const val PAGE_SIZE = 200
        const val FIRST_SYNC_PAGES = 2
        const val MAX_PAGES = 20
        const val MAX_TRC10 = 10
        const val RATE_LIMIT_RETRIES = 3
    }
}

internal fun trc20Asset(contract: String, symbol: String?, name: String?, decimals: Int): Asset {
    val known = TronTokens.KNOWN[contract]
    return tokenAsset(
        chainId = TronChain.ID,
        contract = contract,
        symbol = known?.symbol ?: symbol,
        name = known?.name ?: name,
        decimals = known?.decimals ?: decimals,
        priceId = known?.coingeckoId ?: tokenPriceId(TronChain.COINGECKO_PLATFORM, contract),
    )
}

private fun hexText(hex: String): String? =
    Hex.decode(hex)?.decodeToString()?.takeIf { text -> text.isNotBlank() && text.all { it.code in 0x20..0x7E } }

private fun time(item: JsonElement): Instant =
    item.long("block_timestamp")?.let { Instant.fromEpochMilliseconds(it) } ?: Clock.System.now()

/**
 * TRX effect of one transaction: transfer amounts (TransferContract, call value of smart
 * contract calls) plus the burned fee when [address] is the owner (signer).
 */
internal fun parseTrxTransaction(item: JsonElement, address: String): ChainTransaction? {
    val hash = item.str("txID") ?: return null // internal transactions have no txID
    val contract = item.at("raw_data").arr("contract").firstOrNull() ?: return null
    val value = contract.at("parameter", "value")
    val owner = value.str("owner_address")?.let { TronAddresses.fromHex(it) }
    val receiver = value.str("to_address")?.let { TronAddresses.fromHex(it) }
    val success = item.arr("ret").firstOrNull().str("contractRet").let { it == null || it == "SUCCESS" }
    val amount = when (contract.str("type")) {
        "TransferContract" -> parseBigInteger(value.str("amount"))
        "TriggerSmartContract" -> parseBigInteger(value.str("call_value"))
        else -> BigInteger.ZERO
    }
    val isOwner = owner == address
    val fee = if (isOwner) parseBigInteger(item.arr("ret").firstOrNull().str("fee")) else BigInteger.ZERO
    var delta = BigInteger.ZERO
    if (success) {
        if (isOwner) delta -= amount
        if (receiver == address) delta += amount
    }
    delta -= fee
    if (delta.isZero() && !isOwner) return null
    return accountTransaction(
        TronChain.ID,
        hash,
        time(item),
        TronChain.TRX,
        delta,
        if (isOwner) fee else null,
        isSelfTransfer =
            isOwner && receiver == address,
    )
}

internal fun parseTrc20Transfer(item: JsonElement, address: String, asset: Asset): ChainTransaction? {
    val hash = item.str("transaction_id") ?: return null
    val value = parseBigInteger(item.str("value"))
    if (value.isZero()) return null
    val from = item.str("from")
    val to = item.str("to")
    val delta = when {
        from == address && to == address -> BigInteger.ZERO
        to == address -> value
        from == address -> -value
        else -> return null
    }
    return accountTransaction(TronChain.ID, hash, time(item), asset, delta, null, isSelfTransfer = from == to)
}
