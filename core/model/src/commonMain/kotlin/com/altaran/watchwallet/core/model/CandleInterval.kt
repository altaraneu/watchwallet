package com.altaran.watchwallet.core.model

/** Candlestick interval of the price chart. */
enum class CandleInterval(val minutes: Int) {
    M1(1),
    M5(5),
    M15(15),
    H1(60),
    H4(240),
    D1(1440),
    W1(10080),
    ;

    val isIntraday: Boolean get() = minutes < D1.minutes
}
