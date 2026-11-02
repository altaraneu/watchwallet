package com.altaran.watchwallet.core.pricing

import com.altaran.watchwallet.core.model.CandleInterval
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Binance public market data (no account, no key) in USDT, treated as USD: candles over REST
 * and live candle updates over WebSocket. Far more liquid than Kraken's EUR pairs for most
 * altcoins, so candles are not broken up by empty intervals. Only the pair is sent.
 */
class BinanceMarketData(
    private val http: HttpClient,
    private val restHosts: List<String> = ExchangePrices.DEFAULT_HOSTS,
    private val wsUrl: String = "wss://stream.binance.com:9443/ws",
) {
    /**
     * Up to 1000 candles, or an empty list when the pair is not listed: the most recent ones, those
     * ending before [beforeSeconds], or those starting after [afterSeconds].
     */
    suspend fun candles(base: String, interval: CandleInterval, beforeSeconds: Long? = null, afterSeconds: Long? = null): List<OhlcCandle> {
        val pair = pairOf(base) ?: return emptyList()
        for (host in restHosts) {
            val rows = try {
                http.get("$host/api/v3/klines") {
                    parameter("symbol", pair)
                    parameter("interval", intervalCode(interval))
                    parameter("limit", ExchangePrices.MAX_CANDLES)
                    beforeSeconds?.let { parameter("endTime", it * MILLIS - 1) }
                    afterSeconds?.let { parameter("startTime", it * MILLIS + 1) }
                }.body<JsonArray>()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                continue
            }
            return rows.mapNotNull { row -> parseRest(row as? JsonArray ?: return@mapNotNull null) }
        }
        return emptyList()
    }

    /**
     * Live updates of the current candle over one connection; completes when it drops or stays
     * silent (illiquid pairs can go quiet for a while, so the limit is generous).
     */
    fun liveCandles(base: String, interval: CandleInterval): Flow<OhlcCandle> = flow {
        val pair = pairOf(base)?.lowercase() ?: return@flow
        runCatching {
            http.webSocket("$wsUrl/$pair@kline_${intervalCode(interval)}") {
                while (true) {
                    val frame = withTimeoutOrNull(SILENCE_TIMEOUT) { incoming.receiveCatching().getOrNull() } ?: break
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    parseLive(text)?.let { emit(it) }
                }
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun parseRest(cells: JsonArray): OhlcCandle? {
        val open = cells.getOrNull(0)?.jsonPrimitive?.longOrNull ?: return null
        val n = (OPEN..QUOTE_VOLUME).map { cells.getOrNull(it)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() }
        return candle(open, n[0], n[HIGH - OPEN], n[LOW - OPEN], n[CLOSE - OPEN], n[VOLUME - OPEN], n[QUOTE_VOLUME - OPEN])
    }

    private fun parseLive(text: String): OhlcCandle? {
        val k = runCatching { Json.parseToJsonElement(text).jsonObject["k"]?.jsonObject }.getOrNull() ?: return null
        fun d(key: String) = (k[key]?.jsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val open = k["t"]?.jsonPrimitive?.longOrNull ?: return null
        return candle(open, d("o"), d("h"), d("l"), d("c"), d("v"), d("q"))
    }

    private fun candle(openTime: Long, o: Double?, h: Double?, l: Double?, c: Double?, volume: Double?, quoteVolume: Double?): OhlcCandle? {
        if (o == null || h == null || l == null || c == null) return null
        val vwap = if (volume != null && volume > 0 && quoteVolume != null) quoteVolume / volume else 0.0
        return OhlcCandle(openTime / MILLIS, o, h, l, c, vwap)
    }

    private fun pairOf(base: String): String? {
        val symbol = base.uppercase()
        // Stablecoins have no USDT market; MATIC migrated to POL.
        if (symbol in setOf("USDT", "USDC", "DAI")) return null
        return (if (symbol == "MATIC") "POL" else symbol) + "USDT"
    }

    private fun intervalCode(interval: CandleInterval): String = INTERVAL_CODES.getValue(interval)

    private companion object {
        val SILENCE_TIMEOUT = 90.seconds
        val INTERVAL_CODES = mapOf(
            CandleInterval.M1 to "1m",
            CandleInterval.M5 to "5m",
            CandleInterval.M15 to "15m",
            CandleInterval.H1 to "1h",
            CandleInterval.H4 to "4h",
            CandleInterval.D1 to "1d",
            CandleInterval.W1 to "1w",
        )
        const val MILLIS = 1000L
        const val OPEN = 1
        const val HIGH = 2
        const val LOW = 3
        const val CLOSE = 4
        const val VOLUME = 5
        const val QUOTE_VOLUME = 7
    }
}
