package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.model.CandleInterval
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.pricing.BinanceMarketData
import com.altaran.watchwallet.core.pricing.FxRates
import com.altaran.watchwallet.core.pricing.KrakenMarketData
import com.altaran.watchwallet.core.pricing.OhlcCandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.seconds

sealed interface CandleFeed {
    /**
     * @property depthBars candles the source serves at most for any interval, or `null` when older
     * ones can be requested through [MarketDataRepository.candlePage].
     */
    data class History(val candles: List<OhlcCandle>, val depthBars: Int?) : CandleFeed

    data class Live(val candle: OhlcCandle) : CandleFeed

    /** No exchange market for this asset/currency: fall back to the CoinGecko line chart. */
    data object Unavailable : CandleFeed
}

/**
 * Candlestick data for the price chart: history, then live updates.
 *
 * Binance (USDT pairs, highly liquid) is tried first for fiat currencies, converted from USD
 * with the current ECB reference rate. Kraken is the fallback and serves BTC/ETH as quote
 * currencies; currencies it does not quote directly are converted from EUR. Older candles
 * therefore use today's rate.
 *
 * Verified against the live APIs: Binance has no USDT/USDT pair, so Tether is served by Kraken, and
 * Kraken's OHLC endpoint returns its latest 720 candles whatever `since` says.
 */
class MarketDataRepository(
    private val kraken: KrakenMarketData,
    private val fx: FxRates,
    private val binance: BinanceMarketData,
) {
    fun candles(symbol: String, currency: QuoteCurrency, interval: CandleInterval): Flow<CandleFeed> = flow {
        if (currency.kind == QuoteCurrency.Kind.FIAT && binanceCandles(symbol, currency, interval)) return@flow
        krakenCandles(symbol, currency, interval)
    }

    /** One more page of Binance candles: ending before [beforeSeconds] or starting after [afterSeconds]. */
    suspend fun candlePage(
        symbol: String,
        currency: QuoteCurrency,
        interval: CandleInterval,
        beforeSeconds: Long? = null,
        afterSeconds: Long? = null,
    ): List<OhlcCandle> {
        val factor = if (currency.code == "USD") 1.0 else fx.rate("USD", currency.code)
        return binance.candles(symbol, interval, beforeSeconds, afterSeconds).map { it.scaled(factor) }
    }

    /** Streams Binance candles while the pair is available; returns false when it is not listed. */
    private suspend fun FlowCollector<CandleFeed>.binanceCandles(symbol: String, currency: QuoteCurrency, interval: CandleInterval): Boolean {
        val factor = if (currency.code == "USD") 1.0 else runCatching { fx.rate("USD", currency.code) }.getOrNull() ?: return false
        var listed = false
        var backoff = 1
        while (true) {
            val history = runCatching { binance.candles(symbol, interval) }.getOrDefault(emptyList())
            if (history.isEmpty()) {
                if (!listed) return false
            } else {
                listed = true
                emit(CandleFeed.History(history.map { it.scaled(factor) }, depthBars = null))
                backoff = 1
                binance.liveCandles(symbol, interval).collect { emit(CandleFeed.Live(it.scaled(factor))) }
            }
            delay((backoff * 2).seconds)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
        }
    }

    private suspend fun FlowCollector<CandleFeed>.krakenCandles(symbol: String, currency: QuoteCurrency, interval: CandleInterval) {
        val source = runCatching { resolve(symbol.uppercase(), currency) }.getOrNull()
        if (source == null) {
            emit(CandleFeed.Unavailable)
            return
        }
        var backoff = 1
        var first = true
        while (true) {
            // (Re)load history first, so candles missed while disconnected are filled in.
            val history = runCatching { kraken.candles(symbol, source.quote, interval) }.getOrNull()
            if (!history.isNullOrEmpty()) {
                emit(CandleFeed.History(history.map { it.scaled(source.factor) }, depthBars = KrakenMarketData.MAX_CANDLES))
                backoff = 1
                kraken.liveCandles(symbol, source.quote, interval).collect { emit(CandleFeed.Live(it.scaled(source.factor))) }
            } else if (first) {
                emit(CandleFeed.Unavailable)
            }
            first = false
            delay((backoff * 2).seconds)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
        }
    }

    private data class Source(val quote: String, val factor: Double)

    private companion object {
        const val MAX_BACKOFF_SECONDS = 30
    }

    private suspend fun resolve(symbol: String, currency: QuoteCurrency): Source? = when {
        currency.kind == QuoteCurrency.Kind.CRYPTO -> if (symbol == currency.code) null else Source(currency.code, 1.0)
        currency.code in KrakenMarketData.DIRECT_FIAT -> Source(currency.code, 1.0)
        else -> Source("EUR", fx.rate("EUR", currency.code))
    }
}
