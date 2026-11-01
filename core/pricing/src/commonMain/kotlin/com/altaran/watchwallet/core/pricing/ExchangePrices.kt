package com.altaran.watchwallet.core.pricing

import com.altaran.watchwallet.core.network.Throttle
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
) {
    private val throttle = Throttle(maxConcurrent = 4, minInterval = 50.milliseconds)

    /** Symbols the exchange rejected (not listed); not requested again in this session. */
    private val unlisted = HashSet<String>()

    /** Current USD prices and 24 h change for [symbols]; unknown symbols are left out. */
    suspend fun tickers(symbols: Set<String>): Map<String, ExchangeTicker> {
        val result = HashMap<String, ExchangeTicker>()
        STABLECOINS.intersect(symbols).forEach { result[it] = ExchangeTicker(1.0, 0.0) }
        val wanted = symbols.filter { it !in STABLECOINS && it !in unlisted }
        if (wanted.isEmpty()) return result
        val batch = runCatching { fetchTickers(wanted) }.getOrElse { e ->
            if (e is CancellationException) throw e
            // One unlisted symbol fails the whole batch: ask one by one and remember the misses.
            coroutineScope {
                wanted.map { symbol ->
                    async {
                        runCatching { fetchTickers(listOf(symbol)) }.getOrElse { inner ->
                            if (inner is CancellationException) throw inner
                            unlisted += symbol
                            emptyMap()
                        }
                    }
                }.awaitAll().fold(emptyMap()) { acc, map -> acc + map }
            }
        }
        result += batch
        return result
    }

    /** Daily (or hourly) closes in USD, oldest first, at most [limit] candles. */
    suspend fun history(symbol: String, hourly: Boolean, limit: Int, endTime: Instant? = null): List<ExchangeCandle> {
        if (symbol in STABLECOINS || symbol in unlisted) return emptyList()
        val rows: JsonArray = request("api/v3/klines") {
            parameter("symbol", symbol + QUOTE)
            parameter("interval", if (hourly) "1h" else "1d")
            parameter("limit", limit.coerceIn(1, MAX_CANDLES))
            endTime?.let { parameter("endTime", it.toEpochMilliseconds()) }
        }
        return rows.mapNotNull { row ->
            val cells = row as? JsonArray ?: return@mapNotNull null
            val time = cells.getOrNull(0)?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val close = cells.getOrNull(4)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            ExchangeCandle(Instant.fromEpochMilliseconds(time), close)
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

    private suspend fun request(path: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): JsonArray {
        var last: Exception? = null
        for (host in hosts) {
            try {
                return throttle.run { http.get("$host/$path", block).body() }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                last = e
            }
        }
        throw PriceDataException("Exchange data unavailable", last)
    }

    companion object {
        const val MAX_CANDLES = 1000
        private const val PERCENT = 100.0
        private const val QUOTE = "USDT"
        val DEFAULT_HOSTS = listOf("https://api.binance.com", "https://data-api.binance.vision")
        private val STABLECOINS = setOf("USDT", "USDC")
    }
}
