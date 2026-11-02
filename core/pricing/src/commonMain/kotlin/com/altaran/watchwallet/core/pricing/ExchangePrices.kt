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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Keyless exchange market data (Binance public API, with its official market-data mirror as a
 * fallback host). Only base symbols are sent, never anything about the user's wallets.
 * Stablecoins are priced at 1 USD.
 *
 * An exchange can answer HTTP 200 with frozen data, for example when it halts trading after a hack.
 * Such symbols are left out, so the next source prices them. A symbol counts as frozen when its
 * status is not `TRADING`, its 24 h ticker is old or has no trades, or its last trade id did not
 * change for [FROZEN_AFTER]; candles count as frozen when the newest is much too old.
 */
class ExchangePrices(
    private val http: HttpClient,
    private val hosts: List<String> = DEFAULT_HOSTS,
    private val health: HostHealth = HostHealth(),
    private val now: () -> Instant = { Clock.System.now() },
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
        val halted = haltedSymbols(batch.keys)
        result += batch.filterKeys { it !in halted }
        return result
    }

    private class Status(val at: Instant, val halted: Set<String>, val known: Set<String>)

    /** Symbols the exchange itself reports as not trading. One request every few minutes. */
    private var status: Status? = null
    private val statusLock = Mutex()

    private suspend fun haltedSymbols(symbols: Set<String>): Set<String> = statusLock.withLock {
        if (symbols.isEmpty()) return@withLock emptySet()
        val known = status?.takeIf { now() - it.at < STATUS_FRESH && symbols.all { symbol -> symbol in it.known } }
        (known ?: fetchStatus(symbols))?.halted.orEmpty()
    }

    /** Only a check on top of the prices: its failure says nothing about them, or about the host. */
    private suspend fun fetchStatus(symbols: Set<String>): Status? {
        val host = hosts.firstOrNull { !health.isCoolingDown(it) } ?: return status
        return try {
            val body: JsonObject = throttle.run {
                http.get("$host/api/v3/exchangeInfo") {
                    parameter("symbols", symbols.joinToString(",", "[", "]") { "\"$it$QUOTE\"" })
                }.body()
            }
            val rows = (body["symbols"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val halted = rows.filter { it["status"]?.jsonPrimitive?.contentOrNull != TRADING }
                .mapNotNull { it["symbol"]?.jsonPrimitive?.contentOrNull?.removeSuffix(QUOTE) }.toSet()
            Status(now(), halted, symbols).also { status = it }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            status
        }
    }

    private class LastTrade(val id: Long, val since: Instant)

    private val lastTrades = HashMap<String, LastTrade>()

    /** Guarded by [unlistedLock]; requests for several symbols can run in parallel. */
    private suspend fun isFrozen(symbol: String, row: JsonObject): Boolean {
        val time = now()
        val closeTime = row["closeTime"]?.jsonPrimitive?.longOrNull?.let(Instant::fromEpochMilliseconds)
        if (closeTime != null && time - closeTime > FROZEN_AFTER) return true
        if (row["count"]?.jsonPrimitive?.longOrNull == 0L) return true
        val lastId = row["lastId"]?.jsonPrimitive?.longOrNull ?: return false
        return unlistedLock.withLock {
            val known = lastTrades[symbol]
            if (known == null || known.id != lastId) {
                lastTrades[symbol] = LastTrade(lastId, time)
                false
            } else {
                time - known.since > FROZEN_AFTER
            }
        }
    }

    /**
     * Daily (or hourly) closes in USD, oldest first, at most [limit] candles. Each close is dated
     * at the end of its interval (open time + interval), when that price was really reached; the
     * interval still in progress is left out (its close is not final). Candle charts use their
     * own open-time data ([BinanceMarketData]).
     */
    suspend fun history(symbol: String, hourly: Boolean, limit: Int, endTime: Instant? = null): List<ExchangeCandle> {
        if (symbol in STABLECOINS || isUnlisted(symbol)) return emptyList()
        val rows = try {
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
        val time = now().toEpochMilliseconds()
        val candles = rows.mapNotNull { row ->
            val cells = row as? JsonArray ?: return@mapNotNull null
            val openTime = cells.getOrNull(0)?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val close = cells.getOrNull(4)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val closeTime = openTime + intervalMs
            if (closeTime > time) return@mapNotNull null
            ExchangeCandle(Instant.fromEpochMilliseconds(closeTime), close)
        }
        // The newest closed candle of a live market is at most one interval old.
        val frozen = endTime == null && candles.isNotEmpty() && time - candles.last().time.toEpochMilliseconds() > FROZEN_CANDLE_INTERVALS * intervalMs
        return if (frozen) emptyList() else candles
    }

    /**
     * Closes in USD of the finished [stepMinutes] candles that close between [from] and [to], oldest
     * first, dated at their close like [history]. One request, at most [MAX_CANDLES] candles. A window
     * reaching the present whose newest candle is much too old counts as frozen, and has no closes.
     */
    suspend fun closes(symbol: String, stepMinutes: Int, from: Instant, to: Instant): List<ExchangeCandle> {
        if (symbol in STABLECOINS || isUnlisted(symbol)) return emptyList()
        val intervalMs = stepMinutes * MINUTE_MS
        val rows: JsonArray = try {
            request("api/v3/klines") {
                parameter("symbol", symbol + QUOTE)
                parameter("interval", if (stepMinutes == 1) "1m" else "${stepMinutes / MINUTES_PER_HOUR}h")
                parameter("startTime", from.toEpochMilliseconds() - intervalMs)
                parameter("endTime", to.toEpochMilliseconds() - intervalMs)
                parameter("limit", MAX_CANDLES)
            }
        } catch (e: ResponseException) {
            if (!e.isInvalidSymbol()) throw PriceDataException("Exchange data unavailable", e)
            unlistedLock.withLock { unlisted += symbol }
            return emptyList()
        }
        val time = now().toEpochMilliseconds()
        val candles = rows.mapNotNull { row ->
            val cells = row as? JsonArray ?: return@mapNotNull null
            val openTime = cells.getOrNull(0)?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val close = cells.getOrNull(4)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val closeTime = openTime + intervalMs
            if (closeTime > time) return@mapNotNull null
            ExchangeCandle(Instant.fromEpochMilliseconds(closeTime), close)
        }
        val limit = FROZEN_CANDLE_INTERVALS * intervalMs
        val reachesNow = time - to.toEpochMilliseconds() <= limit && rows.size < MAX_CANDLES
        val frozen = reachesNow && candles.isNotEmpty() && time - candles.last().time.toEpochMilliseconds() > limit
        return if (frozen) emptyList() else candles
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
            if (isFrozen(pair.removeSuffix(QUOTE), obj)) return@mapNotNull null
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
        private const val MINUTE_MS = 60_000L
        private const val MINUTES_PER_HOUR = 60
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS
        private const val PERCENT = 100.0
        private const val QUOTE = "USDT"
        private const val TRADING = "TRADING"
        private const val FROZEN_CANDLE_INTERVALS = 4
        private val FROZEN_AFTER = 15.minutes
        private val STATUS_FRESH = 10.minutes
        val DEFAULT_HOSTS = listOf("https://api.binance.com", "https://data-api.binance.vision")
        private val STABLECOINS = setOf("USDT", "USDC")
    }
}
