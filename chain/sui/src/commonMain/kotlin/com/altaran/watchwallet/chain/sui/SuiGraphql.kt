package com.altaran.watchwallet.chain.sui

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.accountTransaction
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.asList
import com.altaran.watchwallet.chain.common.at
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.parseBigInteger
import com.altaran.watchwallet.chain.common.requireList
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.chain.common.tokenAsset
import com.altaran.watchwallet.chain.common.tokenPriceId
import com.altaran.watchwallet.core.endpoints.BundledEndpoints
import com.altaran.watchwallet.core.endpoints.EndpointProtocol
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Sui via the public GraphQL service (public full-node JSON-RPC was retired in 2026): all coin
 * balances and the transactions that affected the address. `balanceChanges` give exact per-coin
 * deltas, gas included for the sender. Staked SUI (StakedSui objects) is not included.
 */
class SuiAdapter(
    private val http: HttpClient,
    private val graphqlUrl: String = BundledEndpoints.first(SuiChain.ID, EndpointProtocol.SUI_GRAPHQL),
) : ChainAdapter {
    private val throttle = Throttle(maxConcurrent = 3, minInterval = 100.milliseconds, maxJitter = 80.milliseconds)
    private val metadataCache = HashMap<String, JsonElement?>()

    override val family = ChainFamily.SUI
    override val chains = listOf(SuiChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("Sui") {
        val address = address(target)
        val balances = balances(address).amounts
        val hasHistory = transactionsPage(address, null, 1).at("nodes").asList.isNotEmpty()
        val active = hasHistory || balances.any { !it.amount.isZero }
        ProbeResult(active, if (active) listOf(SuiChain.ID) else emptyList(), balances, transactionCount = null)
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("Sui") {
        val address = address(target)
        val held = balances(address)
        val balances = held.amounts
        val assets = balances.associate { (it.asset.contract ?: SuiChain.SUI_COIN_TYPE) to it.asset }
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES

        var truncated = false
        val nodes = mutableListOf<JsonElement>()
        var before: String? = null
        var pages = 0
        paging@ while (true) {
            val page = transactionsPage(address, before, PAGE_SIZE)
            // Each page is oldest-first; walk it newest-first so paging stops at known history.
            for (node in page.at("nodes").asList.asReversed()) {
                if (node.str("digest") in knownTransactions) break@paging
                nodes += node
            }
            val info = page.at("pageInfo")
            before = info.str("startCursor")
            if (info.str("hasPreviousPage") != "true" || before == null) break
            if (++pages >= maxPages) {
                truncated = true
                break
            }
        }
        val transactions = nodes.flatMap { node -> parseTransaction(node, address) { coinType -> assets[coinType] ?: coinAsset(coinType) } }
        val active = transactions.isNotEmpty() || balances.any { !it.amount.isZero }
        val result = SyncResult(
            balances,
            transactions.filterNotNull(),
            activeChains = if (active) listOf(SuiChain.ID) else emptyList(),
            historyTruncated = truncated,
        )
        if (held.complete) result else result.withIncompleteBalances(SuiChain.ID)
    }

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("Sui supports addresses only")

    private class CoinBalances(val amounts: List<AssetAmount>, val complete: Boolean)

    /** Every coin, page by page; verified against the live service (at most 50 per page). */
    private suspend fun balances(address: String): CoinBalances {
        val nodes = mutableListOf<JsonElement>()
        var after: String? = null
        var complete = false
        for (page in 0 until MAX_BALANCE_PAGES) {
            val variables = buildJsonObject {
                put("a", address)
                put("after", after?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            val balances = query(BALANCES_QUERY, variables).at("address", "balances") ?: throw ChainDataException("Sui balances: malformed answer")
            nodes += balances.at("nodes").requireList("Sui balances")
            after = balances.at("pageInfo").str("endCursor")
            if (balances.at("pageInfo").str("hasNextPage") != "true" || after == null) {
                complete = true
                break
            }
        }
        val raw = nodes.mapNotNull { entry ->
            entry.at("coinType").str("repr")?.let { normalizeCoinType(it) to parseBigInteger(entry.str("totalBalance")) }
        }
        val sui = raw.firstOrNull { it.first == SuiChain.SUI_COIN_TYPE }?.second ?: BigInteger.ZERO
        val tokens = raw.filter { (coinType, amount) -> coinType != SuiChain.SUI_COIN_TYPE && !amount.isZero() }
            .mapNotNull { (coinType, amount) -> coinAsset(coinType)?.let { amountOf(it, amount) } }
        return CoinBalances(listOf(amountOf(SuiChain.SUI, sui)) + tokens, complete)
    }

    /** Asset for a coin type, or `null` when the coin has no on-chain metadata (decimals unknown). */
    private suspend fun coinAsset(coinType: String): Asset? {
        if (coinType == SuiChain.SUI_COIN_TYPE) return SuiChain.SUI
        val metadata = metadataCache.getOrPut(coinType) {
            query(METADATA_QUERY, buildJsonObject { put("t", coinType) }).at("coinMetadata").takeIf { it is JsonObject }
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

    private suspend fun transactionsPage(address: String, before: String?, last: Int): JsonElement? = query(
        TRANSACTIONS_QUERY,
        buildJsonObject {
            put("a", address)
            put("before", before?.let { JsonPrimitive(it) } ?: JsonNull)
            put("last", last)
        },
    ).at("address", "transactions")

    private suspend fun query(query: String, variables: JsonObject): JsonElement = throttle.run {
        val response: JsonObject = http.post(graphqlUrl) {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("query", query)
                    put("variables", variables)
                },
            )
        }.body()
        response["errors"]?.let { throw ChainDataException("Sui GraphQL error: $it") }
        response["data"] ?: JsonNull
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val FIRST_SYNC_PAGES = 2
        const val MAX_PAGES = 20
        const val MAX_BALANCE_PAGES = 40

        private const val D = "$"
        val BALANCES_QUERY =
            "query(${D}a: SuiAddress!, ${D}after: String) { address(address: ${D}a) { balances(first: 50, after: ${D}after) { " +
                "pageInfo { hasNextPage endCursor } nodes { coinType { repr } totalBalance } } } }"
        val METADATA_QUERY = "query(${D}t: String!) { coinMetadata(coinType: ${D}t) { decimals symbol name } }"
        val TRANSACTIONS_QUERY =
            "query(${D}a: SuiAddress!, ${D}before: String, ${D}last: Int) { address(address: ${D}a) { " +
                "transactions(last: ${D}last, before: ${D}before, relation: AFFECTED) { pageInfo { hasPreviousPage startCursor } " +
                "nodes { digest effects { timestamp balanceChanges(first: 50) { nodes { owner { address } coinType { repr } amount } } } } } } }"
    }
}

/** `0x000…0002::sui::SUI` → `0x2::sui::SUI` (GraphQL returns fully padded package addresses). */
internal fun normalizeCoinType(repr: String): String {
    val parts = repr.split("::")
    val hex = parts[0].removePrefix("0x").trimStart('0').ifEmpty { "0" }
    return (listOf("0x$hex") + parts.drop(1)).joinToString("::")
}

/** Per-coin deltas of one transaction for [address] from `effects.balanceChanges` (gas included). */
internal suspend fun parseTransaction(
    node: JsonElement,
    address: String,
    assetOf: suspend (String) -> Asset?,
): List<ChainTransaction?> {
    val digest = node.str("digest") ?: return emptyList()
    val effects = node.at("effects")
    val time = effects.str("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Clock.System.now()
    val deltas = LinkedHashMap<String, BigInteger>()
    for (change in effects.at("balanceChanges", "nodes").asList) {
        if (change.at("owner").str("address") != address) continue
        val coinType = normalizeCoinType(change.at("coinType").str("repr") ?: continue)
        deltas[coinType] = (deltas[coinType] ?: BigInteger.ZERO) + parseBigInteger(change.str("amount"))
    }
    return deltas.map { (coinType, delta) ->
        val asset = assetOf(coinType) ?: return@map null
        accountTransaction(SuiChain.ID, digest, time, asset, delta, null)
    }
}
