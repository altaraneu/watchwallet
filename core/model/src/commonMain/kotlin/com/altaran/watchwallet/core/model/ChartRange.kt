package com.altaran.watchwallet.core.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Time range of a chart. [step] is the sampling interval used to build the series. */
enum class ChartRange(val span: Duration?, val step: Duration) {
    H24(24.hours, 1.hours),
    D7(7.days, 2.hours),
    M1(30.days, 12.hours),
    M3(90.days, 1.days),
    Y1(365.days, 1.days),

    /** From January 1st of the current year. */
    YTD(null, 1.days),

    /** From the first transaction. */
    ALL(null, 1.days),
    ;

    /** Ranges up to this length need hourly prices; longer ones use daily prices. */
    val needsHourlyPrices: Boolean get() = span != null && span <= 7.days
}

enum class ChartScale { LINEAR, LOG }
