package com.altaran.watchwallet.core.model

/**
 * Candlestick interval of the price chart.
 *
 * @property visibleBars how many recent candles are visible when the chart opens; the user can
 * pan back and pinch-zoom out to the rest of the loaded history.
 */
enum class CandleInterval(val minutes: Int, val visibleBars: Int) {
    /** 1 minute candles, about 2 hours visible. */
    M1(1, 120),

    /** 5 minute candles, about 12 hours visible. */
    M5(5, 144),

    /** 15 minute candles, about 1 day visible. */
    M15(15, 96),

    /** 1 hour candles, about 5 days visible. */
    H1(60, 120),

    /** 4 hour candles, about 1 month visible. */
    H4(240, 180),

    /** 1 day candles, about half a year visible. */
    D1(1440, 180),

    /** 1 week candles, about 2 years visible. */
    W1(10080, 104),
    ;

    val isIntraday: Boolean get() = minutes < D1.minutes
}
