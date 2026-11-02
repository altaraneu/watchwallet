package com.altaran.watchwallet.core.pricing

import com.altaran.watchwallet.core.network.Throttle
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@Serializable
private data class MarketChart(val prices: List<List<Double>> = emptyList())

@Serializable
private data class MarketRow(
    val id: String,
    val symbol: String,
    val name: String,
    @SerialName("current_price") val currentPrice: Double? = null,
    @SerialName("market_cap") val marketCap: Double? = null,
    @SerialName("price_change_percentage_24h_in_currency") val change24h: Double? = null,
    @SerialName("price_change_percentage_7d_in_currency") val change7d: Double? = null,
    @SerialName("sparkline_in_7d") val sparkline: Sparkline? = null,
)

@Serializable
private data class SearchResponse(val coins: List<SearchCoin> = emptyList())

@Serializable
private data class SearchCoin(
    val id: String,
    val name: String,
    val symbol: String,
    @SerialName("market_cap_rank") val marketCapRank: Int? = null,
)

@Serializable
private data class Sparkline(val price: List<Double?> = emptyList())

/**
 * CoinGecko public API. Works without a key (low rate limit); an optional demo key raises it.
 * Only asset identifiers and the currency are sent — never addresses.
 */
class CoinGeckoProvider(
    private val http: HttpClient,
    private val baseUrl: String = DEFAULT_URL,
    private val apiKey: () -> String? = { null },
) : PriceProvider {
    // The keyless tier allows about 30 calls per minute. Two lanes of 15/min each: chart history
    // (many calls after a sync) can never delay what the user is looking at (prices, markets).
    private val interactive = Throttle(maxConcurrent = 1, minInterval = 4_000.milliseconds)
    private val background = Throttle(maxConcurrent = 1, minInterval = 4_000.milliseconds)

    /** Tokens CoinGecko has no price for (mostly spam), with the time to ask again. */
    private val noPriceUntil = HashMap<String, Instant>()

    /** Long-tail tokens priced recently (`id|currency`): the rate limit is too low to ask every minute. */
    private val tokenFreshUntil = HashMap<String, Instant>()
    private val noPriceLock = Mutex()

    override suspend fun current(priceIds: Collection<String>, currency: String): Map<String, CurrentPrice> {
        val result = HashMap<String, CurrentPrice>()
        currentInBatches(priceIds, currency) { result += it }
        return result
    }

    override suspend fun currentInBatches(priceIds: Collection<String>, currency: String, onBatch: suspend (Map<String, CurrentPrice>) -> Unit) {
        val (tokens, coins) = priceIds.distinct().partition { it.startsWith(TOKEN_PREFIX) }
        var coinFailure: PriceDataException? = null
        coins.chunked(MAX_IDS_PER_CALL).forEach { chunk ->
            try {
                val body: JsonObject = call("simple/price") {
                    parameter("ids", chunk.joinToString(","))
                    parameter("vs_currencies", currency)
                    parameter("include_24hr_change", "true")
                }
                onBatch(chunk.mapNotNull { id -> parsePrice(body[id] as? JsonObject, id, currency)?.let { id to it } }.toMap())
            } catch (e: PriceDataException) {
                coinFailure = e
            }
        }
        // Tokens are not given up because coins failed; a rate limit stops them by itself.
        tokenPrices(tokens, currency, onBatch)
        coinFailure?.let { throw it }
    }

    /**
     * The keyless API accepts a single contract per token price request (checked 2026-11), so those
     * calls go through the background lane. Tokens priced a moment ago keep their stored price, and
     * tokens without a price are not asked again for a while. A failed request says nothing about
     * the token and is simply tried again next time.
     */
    private suspend fun tokenPrices(tokens: List<String>, currency: String, onBatch: suspend (Map<String, CurrentPrice>) -> Unit) {
        val keyless = apiKey() == null
        val now = Clock.System.now()
        val pending = noPriceLock.withLock {
            tokens.filter { id -> noPriceUntil[id]?.let { now < it } != true && tokenFreshUntil["$id|$currency"]?.let { now < it } != true }
        }
        pending.groupBy { it.removePrefix(TOKEN_PREFIX).substringBefore(':') }.forEach { (platform, ids) ->
            ids.chunked(if (keyless) 1 else MAX_IDS_PER_CALL).forEach { chunk ->
                val contracts = chunk.map { it.substringAfterLast(':') }
                val body: JsonObject = try {
                    call("simple/token_price/$platform", if (keyless) background else interactive) {
                        parameter("contract_addresses", contracts.joinToString(","))
                        parameter("vs_currencies", currency)
                        parameter("include_24hr_change", "true")
                    }
                } catch (e: PriceDataException) {
                    if ((e.cause as? ResponseException)?.response?.status?.value == HTTP_TOO_MANY_REQUESTS) return
                    return@forEach
                }
                val prices = chunk.mapNotNull { id -> parsePrice(body[id.substringAfterLast(':')] as? JsonObject, id, currency)?.let { id to it } }.toMap()
                noPriceLock.withLock {
                    chunk.forEach { id ->
                        if (id in prices) tokenFreshUntil["$id|$currency"] = now + TOKEN_PRICE_TTL else noPriceUntil[id] = now + NO_PRICE_RETRY
                    }
                }
                if (prices.isNotEmpty()) onBatch(prices)
            }
        }
    }

    override suspend fun history(priceId: String, currency: String, days: Int?): List<PricePoint> {
        val path = if (priceId.startsWith(TOKEN_PREFIX)) {
            val (platform, contract) = priceId.removePrefix(TOKEN_PREFIX).split(':', limit = 2)
            "coins/$platform/contract/$contract/market_chart"
        } else {
            "coins/$priceId/market_chart"
        }
        val chart: MarketChart = call(path, background) {
            parameter("vs_currency", currency)
            // The keyless API serves at most 365 days of history.
            parameter("days", (days ?: MAX_KEYLESS_DAYS).coerceAtMost(if (apiKey() == null) MAX_KEYLESS_DAYS else Int.MAX_VALUE))
            if ((days ?: MAX_KEYLESS_DAYS) > HOURLY_MAX_DAYS) parameter("interval", "daily")
        }
        return chart.prices.mapNotNull { point ->
            if (point.size < 2) null else PricePoint(Instant.fromEpochMilliseconds(point[0].toLong()), point[1])
        }
    }

    override suspend fun markets(coinIds: Collection<String>, currency: String): List<MarketInfo> =
        coinIds.filterNot { it.startsWith(TOKEN_PREFIX) }.distinct().chunked(MAX_IDS_PER_CALL).flatMap { chunk ->
            call<List<MarketRow>>("coins/markets") {
                parameter("vs_currency", currency)
                parameter("ids", chunk.joinToString(","))
                parameter("sparkline", "true")
                parameter("price_change_percentage", "24h,7d")
            }.mapNotNull { row ->
                MarketInfo(
                    priceId = row.id,
                    symbol = row.symbol.uppercase(),
                    name = row.name,
                    price = row.currentPrice ?: return@mapNotNull null,
                    change24hPercent = row.change24h,
                    change7dPercent = row.change7d,
                    marketCap = row.marketCap,
                    sparkline7d = row.sparkline?.price.orEmpty().filterNotNull(),
                )
            }
        }

    override suspend fun search(query: String): List<CoinSearchResult> {
        if (query.isBlank()) return emptyList()
        val response: SearchResponse = call("search") { parameter("query", query.trim()) }
        return response.coins.take(MAX_SEARCH_RESULTS).map { CoinSearchResult(it.id, it.symbol.uppercase(), it.name, it.marketCapRank) }
    }

    private suspend inline fun <reified T> call(
        path: String,
        throttle: Throttle = interactive,
        crossinline block: HttpRequestBuilder.() -> Unit,
    ): T = try {
        throttle.run {
            http.get("${baseUrl.trimEnd('/')}/$path") {
                apiKey()?.takeIf { it.isNotBlank() }?.let { header("x-cg-demo-api-key", it) }
                block()
            }.body()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        throw PriceDataException("CoinGecko request failed: $path", e)
    }

    private fun parsePrice(entry: JsonObject?, id: String, currency: String): CurrentPrice? {
        val price = entry?.get(currency)?.jsonPrimitive?.doubleOrNull ?: return null
        val change = entry["${currency}_24h_change"]?.jsonPrimitive?.doubleOrNull
        return CurrentPrice(id, currency, price, change)
    }

    companion object {
        const val DEFAULT_URL = "https://api.coingecko.com/api/v3"
        private const val TOKEN_PREFIX = "token:"
        private const val MAX_IDS_PER_CALL = 100
        private val NO_PRICE_RETRY = 12.hours
        private val TOKEN_PRICE_TTL = 10.minutes
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val MAX_KEYLESS_DAYS = 365
        private const val HOURLY_MAX_DAYS = 90
        private const val MAX_SEARCH_RESULTS = 25
    }
}
