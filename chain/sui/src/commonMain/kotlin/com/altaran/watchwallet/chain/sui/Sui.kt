package com.altaran.watchwallet.chain.sui

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.JsonRpcClient
import com.altaran.watchwallet.chain.common.accountTransaction
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.arr
import com.altaran.watchwallet.chain.common.asList
import com.altaran.watchwallet.chain.common.at
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.long
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.parseBigInteger
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.chain.common.tokenAsset
import com.altaran.watchwallet.chain.common.tokenPriceId
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object SuiChain {
    val ID = ChainId("sui")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.SUI,
        name = "Sui",
        nativeSymbol = "SUI",
        nativeDecimals = 9,
        nativePriceId = "sui",
        explorerTxUrl = "https://suiscan.xyz/mainnet/tx/{tx}",
        explorerAddressUrl = "https://suiscan.xyz/mainnet/account/{address}",
        color = 0xFF4DA2FF,
    )

    val SUI: Asset = nativeAsset(CHAIN)

    const val DEFAULT_RPC_URL = "https://fullnode.mainnet.sui.io"
    const val SUI_COIN_TYPE = "0x2::sui::SUI"
    const val COINGECKO_PLATFORM = "sui"
}

/**
 * Sui addresses: `0x` + 64 hex characters (32 bytes). The format has no checksum, so only the
 * shape can be validated. The same shape is used by Aptos; the classifier must ask the user.
 */
object SuiAddresses {
    private val SHAPE = Regex("^0[xX][0-9a-fA-F]{64}$")

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        if (!SHAPE.matches(text)) return null
        return WatchTarget.Address(ChainFamily.SUI, SuiChain.ID, "0x" + text.substring(2).lowercase())
    }
}

/**
 * Sui via public full-node JSON-RPC: all coin balances and transaction blocks sent from or
 * received by the address. `balanceChanges` give exact per-coin deltas (gas included).
 * Staked SUI (StakedSui objects) is not included in the balance.
 */
class SuiAdapter(
    http: HttpClient,
    rpcUrl: String = SuiChain.DEFAULT_RPC_URL,
) : ChainAdapter {
    private val rpc = JsonRpcClient(
        http,
        listOf(rpcUrl),
        Throttle(maxConcurrent = 3, minInterval = 100.milliseconds, maxJitter = 80.milliseconds),
        rateLimitRetries = 2,
    )
    private val metadataCache = HashMap<String, JsonElement?>()

    override val family = ChainFamily.SUI
    override val chains = listOf(SuiChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("Sui") {
        val address = address(target)
        val balances = balances(address)
        val hasHistory = query(address, "FromAddress", null, 1).arr("data").isNotEmpty() ||
            query(address, "ToAddress", null, 1).arr("data").isNotEmpty()
        val active = hasHistory || balances.any { !it.amount.isZero }
        ProbeResult(active, if (active) listOf(SuiChain.ID) else emptyList(), balances, transactionCount = null)
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("Sui") {
        val address = address(target)
        val balances = balances(address)
        val assets = balances.associate { (it.asset.contract ?: SuiChain.SUI_COIN_TYPE) to it.asset }
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES

        var truncated = false
        val blocks = LinkedHashMap<String, JsonElement>()
        for (filter in listOf("FromAddress", "ToAddress")) {
            var cursor: String? = null
            var pages = 0
            paging@ while (true) {
                val page = query(address, filter, cursor, PAGE_SIZE)
                for (block in page.arr("data")) {
                    val digest = block.str("digest") ?: continue
                    if (digest in knownTransactions) break@paging
                    blocks.getOrPut(digest) { block }
                }
                cursor = page.str("nextCursor")
                if (page.str("hasNextPage") != "true" || cursor == null) break
                if (++pages >= maxPages) {
                    truncated = true
                    break
                }
            }
        }
        val transactions = blocks.values.flatMap { block ->
            parseBlock(block, address) { coinType ->
                assets[coinType]
                    ?: coinAsset(coinType)
            }
        }
        val active = transactions.isNotEmpty() || balances.any { !it.amount.isZero }
        SyncResult(
            balances,
            transactions.filterNotNull(),
            activeChains = if (active) listOf(SuiChain.ID) else emptyList(),
            historyTruncated = truncated,
        )
    }

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("Sui supports addresses only")

    private suspend fun balances(address: String): List<AssetAmount> {
        val all = rpc.call("suix_getAllBalances", buildJsonArray { add(JsonPrimitive(address)) }).asList
        var sui = BigInteger.ZERO
        val tokens = mutableListOf<AssetAmount>()
        for (entry in all) {
            val coinType = entry.str("coinType") ?: continue
            val raw = parseBigInteger(entry.str("totalBalance"))
            if (coinType == SuiChain.SUI_COIN_TYPE) {
                sui = raw
                continue
            }
            if (raw.isZero()) continue
            tokens += amountOf(coinAsset(coinType) ?: continue, raw)
        }
        return listOf(amountOf(SuiChain.SUI, sui)) + tokens
    }

    /** Asset for a coin type, or `null` when the coin has no on-chain metadata (decimals unknown). */
    private suspend fun coinAsset(coinType: String): Asset? {
        if (coinType == SuiChain.SUI_COIN_TYPE) return SuiChain.SUI
        val metadata = metadataCache.getOrPut(coinType) {
            rpc.call(
                "suix_getCoinMetadata",
                buildJsonArray {
                    add(JsonPrimitive(coinType))
                },
            ).takeIf { it is JsonObject }
        } ?: return null
        val decimals = metadata.str("decimals")?.toIntOrNull() ?: return null
        return tokenAsset(
            chainId = SuiChain.ID,
            contract = coinType,
            symbol = metadata.str("symbol"),
            name = metadata.str("name"),
            decimals = decimals,
            priceId = tokenPriceId(SuiChain.COINGECKO_PLATFORM, coinType),
        )
    }

    private suspend fun query(address: String, filter: String, cursor: String?, limit: Int): JsonElement = rpc.call(
        "suix_queryTransactionBlocks",
        buildJsonArray {
            add(
                buildJsonObject {
                    putJsonObject("filter") { put(filter, address) }
                    putJsonObject("options") {
                        put("showBalanceChanges", true)
                        put("showEffects", true)
                        put("showInput", false)
                    }
                },
            )
            add(cursor?.let { JsonPrimitive(it) } ?: JsonNull)
            add(JsonPrimitive(limit))
            add(JsonPrimitive(true))
        },
    )

    private companion object {
        const val PAGE_SIZE = 50
        const val FIRST_SYNC_PAGES = 2
        const val MAX_PAGES = 20
    }
}

/**
 * Per-coin deltas of one transaction block for [address] from `balanceChanges`. The gas fee
 * (computation + storage − rebate) is reported for the gas owner.
 */
internal suspend fun parseBlock(
    block: JsonElement,
    address: String,
    assetOf: suspend (String) -> Asset?,
): List<ChainTransaction?> {
    val digest = block.str("digest") ?: return emptyList()
    val time = block.long("timestampMs")?.let { Instant.fromEpochMilliseconds(it) } ?: Clock.System.now()
    val gasOwner = block.at("effects", "gasObject", "owner").str("AddressOwner")
    val gas = block.at("effects", "gasUsed")
    val fee = if (gasOwner == address) {
        parseBigInteger(gas.str("computationCost")) + parseBigInteger(gas.str("storageCost")) -
            parseBigInteger(gas.str("storageRebate"))
    } else {
        null
    }
    val deltas = LinkedHashMap<String, BigInteger>()
    for (change in block.arr("balanceChanges")) {
        if (change.at("owner").str("AddressOwner") != address) continue
        val coinType = change.str("coinType") ?: continue
        deltas[coinType] = (deltas[coinType] ?: BigInteger.ZERO) + parseBigInteger(change.str("amount"))
    }
    if (fee != null && SuiChain.SUI_COIN_TYPE !in deltas) deltas[SuiChain.SUI_COIN_TYPE] = BigInteger.ZERO
    return deltas.map { (coinType, delta) ->
        val asset = assetOf(coinType) ?: return@map null
        val isSui = coinType == SuiChain.SUI_COIN_TYPE
        accountTransaction(SuiChain.ID, digest, time, asset, delta, if (isSui) fee else null)
    }
}
