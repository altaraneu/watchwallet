package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.model.CandleInterval
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.pricing.FxRates
import com.altaran.watchwallet.core.pricing.KrakenMarketData
import com.altaran.watchwallet.core.pricing.OhlcCandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
        val history = runCatching { kraken.candles(symbol, source.quote, interval) }.getOrNull()
        if (history.isNullOrEmpty()) {
            emit(CandleFeed.Unavailable)
            return@flow
        }
        emit(CandleFeed.History(history.map { it.scaled(source.factor) }))
        kraken.liveCandles(symbol, source.quote, interval).collect { emit(CandleFeed.Live(it.scaled(source.factor))) }
    }

    private data class Source(val quote: String, val factor: Double)

    private suspend fun resolve(symbol: String, currency: QuoteCurrency): Source? = when {
        currency.kind == QuoteCurrency.Kind.CRYPTO -> if (symbol == currency.code) null else Source(currency.code, 1.0)
        currency.code in KrakenMarketData.DIRECT_FIAT -> Source(currency.code, 1.0)
        else -> Source("EUR", fx.rate("EUR", currency.code))
    }
}
