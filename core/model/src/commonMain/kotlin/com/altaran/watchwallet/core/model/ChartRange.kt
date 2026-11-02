package com.altaran.watchwallet.core.model

import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Time range of a chart. [step] is the sampling interval used to build the series. */
enum class ChartRange(val span: Duration?, val step: Duration) {
    H1(1.hours, 1.minutes),
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

    companion object {
        /** A chip is highlighted only while the view is within this factor of its length. */
        private const val CHIP_TOLERANCE = 1.5

        /**
         * The range chip matching a visible window of [visible] length, or `null` when no chip is
         * close (for example 3 days: neither 24H nor 7D describes it).
         *
         * The [selected] chip wins while it still fits, so a chip stays lit after tapping it even
         * if a neighbour is nominally closer. Anything shorter than the shortest chip belongs to
         * that chip: fully zoomed in to single minutes the 1H chip stays lit.
         *
         * @param spanOf length of a chip now; ranges without a fixed span (YTD, ALL) depend on the
         * date and on the data. `null` leaves the chip out.
         */
        fun chipFor(visible: Duration, selected: ChartRange?, ranges: List<ChartRange>, spanOf: (ChartRange) -> Duration?): ChartRange? {
            val spans = ranges.mapNotNull { range -> spanOf(range)?.takeIf { it.isPositive() }?.let { range to it } }
            if (spans.isEmpty() || !visible.isPositive()) return null
            fun distance(span: Duration) = max(visible / span, span / visible)
            spans.firstOrNull { it.first == selected }?.let { if (distance(it.second) <= CHIP_TOLERANCE) return selected }
            val shortest = spans.minBy { it.second }
            if (visible <= shortest.second) return shortest.first
            return spans.minBy { distance(it.second) }.takeIf { distance(it.second) <= CHIP_TOLERANCE }?.first
        }
    }
}

enum class ChartScale { LINEAR, LOG }
