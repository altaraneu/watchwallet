package com.altaran.watchwallet.core.pricing

import com.altaran.watchwallet.core.network.HostHealth
import com.altaran.watchwallet.core.network.Throttle
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Keyless exchange market data (Binance public API, with its official market-data mirror as a
 * fallback host). Only base symbols are sent, never anything about the user's wallets.
 * Stablecoins are priced at 1 USD.
 */
class ExchangePrices(
    private val http: HttpClient,
    private val hosts: List<String> = DEFAULT_HOSTS,
    private val health: HostHealth = HostHealth(),
) {
    private val throttle = Throttle(maxConcurrent = 4, minInterval = 50.milliseconds)

    /**
     * Symbols the exchange rejected as invalid (HTTP 400: not listed); not requested again in this
     * session. Guarded by [unlistedLock] (written from parallel requests).
     */
    private val unlisted = HashSet<String>()
    private val unlistedLock = Mutex()

    private suspend fun isUnlisted(symbol: String): Boolean = unlistedLock.withLock { symbol in unlisted }

    /** Current USD prices and 24 h change for [symbols]; unknown symbols are left out. */
    suspend fun tickers(symbols: Set<String>): Map<String, ExchangeTicker> {
        val result = HashMap<String, ExchangeTicker>()
        STABLECOINS.intersect(symbols).forEach { result[it] = ExchangeTicker(1.0, 0.0) }
        val known = unlistedLock.withLock { unlisted.toSet() }
        val wanted = symbols.filter { it !in STABLECOINS && it !in known }
        if (wanted.isEmpty()) return result
        val batch: Map<String, ExchangeTicker> = try {
            fetchTickers(wanted)
        } catch (e: ResponseException) {
            if (!e.isInvalidSymbol()) throw PriceDataException("Exchange data unavailable", e)
            // One unlisted symbol fails the whole batch: ask one by one and remember the misses.
            // Other failures (network, rate limit) are not remembered: the symbol may be fine.
            coroutineScope {
                wanted.map { symbol ->
                    async {
                        try {
                            fetchTickers(listOf(symbol))
                        } catch (inner: ResponseException) {
                            if (inner.isInvalidSymbol()) unlistedLock.withLock { unlisted += symbol }
                            emptyMap<String, ExchangeTicker>()
                        } catch (
                            @Suppress("SwallowedException") inner: PriceDataException,
                        ) {
                            emptyMap<String, ExchangeTicker>()
                        }
                    }
                }.awaitAll().fold(emptyMap<String, ExchangeTicker>()) { acc, map -> acc + map }
            }
        }
        result += batch
        return result
    }

    /**
     * Daily (or hourly) closes in USD, oldest first, at most [limit] candles. Each close is dated
     * at the end of its interval (open time + interval), when that price was really reached; the
     * interval still in progress is left out (its close is not final). Candle charts use their
     * own open-time data ([BinanceMarketData]).
     */
    suspend fun history(symbol: String, hourly: Boolean, limit: Int, endTime: Instant? = null): List<ExchangeCandle> {
        if (symbol in STABLECOINS || isUnlisted(symbol)) return emptyList()
        val rows: JsonArray = try {
            request("api/v3/klines") {
                parameter("symbol", symbol + QUOTE)
                parameter("interval", if (hourly) "1h" else "1d")
                parameter("limit", limit.coerceIn(1, MAX_CANDLES))
                endTime?.let { parameter("endTime", it.toEpochMilliseconds()) }
            }
        } catch (e: ResponseException) {
            if (!e.isInvalidSymbol()) throw PriceDataException("Exchange data unavailable", e)
            unlistedLock.withLock { unlisted += symbol }
            return emptyList()
        }
        val intervalMs = if (hourly) HOUR_MS else DAY_MS
        val now = Clock.System.now().toEpochMilliseconds()
        return rows.mapNotNull { row ->
            val cells = row as? JsonArray ?: return@mapNotNull null
            val openTime = cells.getOrNull(0)?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val close = cells.getOrNull(4)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val closeTime = openTime + intervalMs
            if (closeTime > now) return@mapNotNull null
            ExchangeCandle(Instant.fromEpochMilliseconds(closeTime), close)
        }
    }

    private suspend fun fetchTickers(symbols: List<String>): Map<String, ExchangeTicker> {
        val list = symbols.joinToString(",", "[", "]") { "\"$it$QUOTE\"" }
        val rows: JsonArray = request("api/v3/ticker/24hr") {
            parameter("symbols", list)
            parameter("type", "MINI")
        }
        return rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val pair = obj["symbol"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val last = obj["lastPrice"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val open = obj["openPrice"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            val change = open?.takeIf { it > 0 }?.let { (last - it) / it * PERCENT }
            pair.removeSuffix(QUOTE) to ExchangeTicker(last, change)
        }.toMap()
    }

    /**
     * GET on the first host that answers. An invalid-symbol rejection (HTTP 400) is the same on
     * every host and is rethrown as the [ResponseException] at once; other failures (including a
     * regional block such as HTTP 451) try the next host, are remembered for later calls and end in
     * [PriceDataException].
     */
    private suspend fun request(path: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): JsonArray = try {
        health.firstAvailable(hosts, { (it as? ResponseException)?.isInvalidSymbol() != true }) { host ->
            throttle.run { http.get("$host/$path", block).body() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseException) {
        if (e.isInvalidSymbol()) throw e
        throw PriceDataException("Exchange data unavailable", e)
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        throw PriceDataException("Exchange data unavailable", e)
    }

    private fun ResponseException.isInvalidSymbol(): Boolean = response.status.value == HTTP_BAD_REQUEST

    companion object {
        const val MAX_CANDLES = 1000
        private const val HTTP_BAD_REQUEST = 400
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS
        private const val PERCENT = 100.0
        private const val QUOTE = "USDT"
        val DEFAULT_HOSTS = listOf("https://api.binance.com", "https://data-api.binance.vision")
        private val STABLECOINS = setOf("USDT", "USDC")
    }
}
