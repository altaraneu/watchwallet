package com.altaran.watchwallet.core.pricing

import com.altaran.watchwallet.core.model.CandleInterval
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** OHLC candle; [time] = interval start in UTC seconds. */
data class OhlcCandle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    /** Volume-weighted average price of the interval (0 when unknown). */
    val vwap: Double = 0.0,
) {
    fun scaled(factor: Double) = OhlcCandle(time, open * factor, high * factor, low * factor, close * factor, vwap * factor)
}

/**
 * Kraken public market data (no account, no key): candles over REST and live candle updates
 * over WebSocket v2. Only the trading pair (for example `BTC/EUR`) is sent.
 */
class KrakenMarketData(
    private val http: HttpClient,
    private val restUrl: String = "https://api.kraken.com/0/public",
    private val wsUrl: String = "wss://ws.kraken.com/v2",
) {
    /** Up to 720 most recent candles. */
    suspend fun candles(base: String, quote: String, interval: CandleInterval): List<OhlcCandle> {
        val body: JsonObject = http.get("$restUrl/OHLC") {
            parameter("pair", restBase(base) + restQuote(quote))
            parameter("interval", interval.minutes)
        }.body()
        val errors = body["error"]?.jsonArray.orEmpty()
        if (errors.isNotEmpty()) throw PriceDataException("Kraken: ${errors.joinToString()}")
        val result = body["result"]?.jsonObject ?: throw PriceDataException("Kraken: no result")
        val rows = result.entries.firstOrNull { it.key != "last" }?.value?.jsonArray ?: return emptyList()
        return rows.mapNotNull { row ->
            val a = row.jsonArray
            if (a.size < 5) return@mapNotNull null
            OhlcCandle(
                time = a[0].jsonPrimitive.longOrNull ?: return@mapNotNull null,
                open = a[1].jsonPrimitive.content.toDouble(),
                high = a[2].jsonPrimitive.content.toDouble(),
                low = a[3].jsonPrimitive.content.toDouble(),
                close = a[4].jsonPrimitive.content.toDouble(),
                vwap = a.getOrNull(5)?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
            )
        }
    }

    /**
     * Live updates of the current candle over one connection. Completes when the connection drops
     * or goes silent (Kraken sends a heartbeat every second; a sleeping phone or a lost network
     * can leave a socket open without traffic). The caller reconnects and backfills the gap.
     */
    fun liveCandles(base: String, quote: String, interval: CandleInterval): Flow<OhlcCandle> = flow {
        val symbol = "${wsBase(base)}/$quote"
        runCatching {
            http.webSocket(wsUrl) {
                send(
                    buildJsonObject {
                        put("method", "subscribe")
                        put(
                            "params",
                            buildJsonObject {
                                put("channel", "ohlc")
                                put("symbol", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(symbol)) })
                                put("interval", interval.minutes)
                            },
                        )
                    }.toString(),
                )
                while (true) {
                    val frame = withTimeoutOrNull(SILENCE_TIMEOUT) { incoming.receiveCatching().getOrNull() } ?: break
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    parseLive(text).forEach { emit(it) }
                }
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    private fun parseLive(text: String): List<OhlcCandle> {
        val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        if (message["channel"]?.jsonPrimitive?.content != "ohlc") return emptyList()
        val data = message["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            val o = item.jsonObject
            val begin = o["interval_begin"]?.jsonPrimitive?.content ?: return@mapNotNull null
            OhlcCandle(
                time = runCatching { Instant.parse(begin).epochSeconds }.getOrNull() ?: return@mapNotNull null,
                open = o["open"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                high = o["high"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                low = o["low"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                close = o["close"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                vwap = o["vwap"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
        }
    }

    companion object {
        private val SILENCE_TIMEOUT = 15.seconds

        /** Kraken REST still uses legacy asset codes for a few coins. */
        fun restBase(symbol: String): String = when (symbol.uppercase()) {
            "BTC" -> "XBT"
            "DOGE" -> "XDG"
            else -> symbol.uppercase()
        }

        private fun restQuote(symbol: String): String = restBase(symbol)

        fun wsBase(symbol: String): String = symbol.uppercase()

        /** Fiat quotes Kraken lists directly. Other fiat currencies are converted from EUR. */
        val DIRECT_FIAT = setOf("USD", "EUR", "GBP", "CHF", "CAD", "AUD", "JPY")
    }
}

/** Reference FX rates (European Central Bank via the open Frankfurter API). Only currency codes are sent. */
class FxRates(
    private val http: HttpClient,
    private val urls: List<String> = listOf("https://api.frankfurter.dev/v1/latest", "https://api.frankfurter.app/latest"),
) {
    private val cache = mutableMapOf<String, Double>()

    /** How many units of [to] one [from] buys, for example EUR → CZK ≈ 24.5. */
    suspend fun rate(from: String, to: String): Double {
        if (from == to) return 1.0
        val key = "$from>$to"
        cache[key]?.let { return it }
        for (url in urls) {
            val rate = runCatching {
                val body: JsonObject = http.get(url) {
                    parameter("base", from)
                    parameter("from", from)
                    parameter("symbols", to)
                    parameter("to", to)
                }.body()
                body["rates"]?.jsonObject?.get(to)?.jsonPrimitive?.doubleOrNull
            }.getOrNull()
            if (rate != null) {
                cache[key] = rate
                return rate
            }
        }
        throw PriceDataException("FX rate $from→$to unavailable")
    }
}
