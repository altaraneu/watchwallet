package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.model.CandleInterval
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.pricing.FxRates
import com.altaran.watchwallet.core.pricing.KrakenMarketData
import com.altaran.watchwallet.core.pricing.OhlcCandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.seconds

sealed interface CandleFeed {
    data class History(val candles: List<OhlcCandle>) : CandleFeed

    data class Live(val candle: OhlcCandle) : CandleFeed

    /** No exchange market for this asset/currency: fall back to the CoinGecko line chart. */
    data object Unavailable : CandleFeed
}

/**
 * Candlestick data for the price chart: history from Kraken, then live updates.
 *
 * Currencies Kraken does not quote directly (CZK, PLN, HUF, SEK, NOK, DKK…) are converted from
 * EUR with the current ECB reference rate; older candles therefore use today's rate.
 */
class MarketDataRepository(
    private val kraken: KrakenMarketData,
    private val fx: FxRates,
) {
    fun candles(symbol: String, currency: QuoteCurrency, interval: CandleInterval): Flow<CandleFeed> = flow {
        val source = runCatching { resolve(symbol.uppercase(), currency) }.getOrNull()
        if (source == null) {
            emit(CandleFeed.Unavailable)
            return@flow
        }
        var backoff = 1
        var first = true
        while (true) {
            // (Re)load history first, so candles missed while disconnected are filled in.
            val history = runCatching { kraken.candles(symbol, source.quote, interval) }.getOrNull()
            if (!history.isNullOrEmpty()) {
                emit(CandleFeed.History(history.map { it.scaled(source.factor) }))
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
