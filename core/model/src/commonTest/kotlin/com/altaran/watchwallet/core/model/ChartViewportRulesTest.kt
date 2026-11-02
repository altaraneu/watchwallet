package com.altaran.watchwallet.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ChartViewportRulesTest {
    private val ranges = ChartRange.entries

    private fun chip(visible: Duration, selected: ChartRange = ChartRange.M1) = ChartRange.chipFor(visible, selected, ranges) { range ->
        when (range) {
            ChartRange.ALL -> 1000.days
            ChartRange.YTD -> 300.days
            else -> range.span
        }
    }

    @Test
    fun chipFollowsTheVisibleSpan() {
        assertEquals(ChartRange.D7, chip(6.days))
        assertEquals(ChartRange.H24, chip(20.hours))
        assertEquals(ChartRange.ALL, chip(1100.days))
    }

    @Test
    fun noChipWhenNothingFits() {
        // 3 to 4 days are neither 24H nor 7D, also when 7D was the selected chip.
        assertNull(chip(3.days, selected = ChartRange.D7))
        assertNull(chip(4.days, selected = ChartRange.D7))
        assertNull(chip(5.hours))
    }

    @Test
    fun fullyZoomedInBelongsToTheShortestChip() {
        assertEquals(ChartRange.H1, chip(6.minutes))
        assertEquals(ChartRange.H1, chip(1.minutes, selected = ChartRange.ALL))
    }

    @Test
    fun selectedChipWinsWhileItFits() {
        // Y1 is nominally closer to 320 days than YTD (300), and the other way around.
        assertEquals(ChartRange.YTD, chip(330.days, selected = ChartRange.YTD))
        assertEquals(ChartRange.Y1, chip(310.days, selected = ChartRange.Y1))
    }

    @Test
    fun chipsWithoutALengthAreLeftOut() {
        val result = ChartRange.chipFor(2000.days, ChartRange.ALL, ranges) { if (it == ChartRange.ALL) null else it.span }
        assertNull(result)
    }

    private fun next(current: CandleInterval, spanMinutes: Long, depth: Int? = null, barsBefore: Int = 500, ageMinutes: Long = spanMinutes): CandleInterval =
        CandleViewport.next(current, (spanMinutes / current.minutes).toInt(), spanMinutes * 60, ageMinutes * 60, depth, barsBefore)

    @Test
    fun zoomingOutMakesCandlesCoarser() {
        assertEquals(CandleInterval.H1, next(CandleInterval.H1, 100 * 60))
        assertEquals(CandleInterval.H4, next(CandleInterval.H1, 300 * 60))
        // A fast zoom out may skip intervals.
        assertEquals(CandleInterval.H1, next(CandleInterval.M1, 200 * 60))
    }

    @Test
    fun zoomingInMakesCandlesFiner() {
        assertEquals(CandleInterval.M15, next(CandleInterval.H1, 30 * 60))
        assertEquals(CandleInterval.M1, next(CandleInterval.M1, 6))
        assertEquals(CandleInterval.M1, next(CandleInterval.M5, 10))
    }

    @Test
    fun theIntervalNeverFlipsBackAndForthForTheSameWindow() {
        // Every window, every starting interval: following the rule twice lands where once did.
        var span = 1L
        while (span < 10_000_000L) {
            CandleInterval.entries.forEach { start ->
                var interval = start
                val seen = mutableListOf(interval)
                repeat(8) {
                    interval = next(interval, span)
                    seen += interval
                }
                val settled = seen.last()
                assertEquals(settled, next(settled, span), "span $span from $start: $seen")
                assertTrue(seen.takeLast(3).distinct().size == 1, "span $span from $start: $seen")
            }
            span = span * 11 / 10 + 1
        }
    }

    @Test
    fun aSourceWithoutPagingMovesCoarserAtItsLimitAndStays() {
        // Kraken: 720 hours are about 30 days. At the left edge the next interval reaches further.
        val span = 120L * 60
        val age = 30L * 24 * 60
        assertEquals(CandleInterval.H4, next(CandleInterval.H1, span, depth = 720, barsBefore = 2, ageMinutes = age))
        // Few bars at H4 would normally ask for H1, but that does not reach this far back.
        val narrow = 25L * 60
        assertEquals(CandleInterval.H4, next(CandleInterval.H4, narrow * 4, depth = 720, barsBefore = 100, ageMinutes = age))
        // Recent windows still can go finer.
        assertEquals(CandleInterval.H1, next(CandleInterval.H4, 100L * 60, depth = 720, barsBefore = 100, ageMinutes = 10L * 24 * 60))
    }

    @Test
    fun aSourceWithPagingStaysAtItsIntervalAtTheEdge() {
        assertEquals(CandleInterval.H1, next(CandleInterval.H1, 100 * 60, depth = null, barsBefore = 0))
    }

    private val hour = 3600L
    private fun times(vararg hours: Long) = hours.map { it * hour }

    @Test
    fun olderCandlesAreRequestedBeforeTheViewRunsOut() {
        val loaded = (0L until 300L).map { it * hour }
        val fetch = CandleViewport.nextFetch(loaded, CandleInterval.H1, 50 * hour, 100 * hour, barsBefore = 50, bars = 50, canLoadOlder = true)
        assertEquals(CandleViewport.Fetch.Older(0), fetch)
        assertNull(CandleViewport.nextFetch(loaded, CandleInterval.H1, 150 * hour, 200 * hour, barsBefore = 150, bars = 50, canLoadOlder = true))
        assertNull(CandleViewport.nextFetch(loaded, CandleInterval.H1, 50 * hour, 100 * hour, barsBefore = 50, bars = 50, canLoadOlder = false))
    }

    @Test
    fun holesNextToTheViewAreClosedFirst() {
        val loaded = times(100, 101, 102, 500, 501)
        val fetch = CandleViewport.nextFetch(loaded, CandleInterval.H1, 101 * hour, 102 * hour, barsBefore = 1, bars = 2, canLoadOlder = true)
        assertEquals(CandleViewport.Fetch.Newer(102 * hour), fetch)
        // A hole that could not be filled is real: the market had no trades.
        val next = CandleViewport.nextFetch(loaded, CandleInterval.H1, 101 * hour, 102 * hour, 1, 2, canLoadOlder = true, ignoreGapsAt = setOf(102 * hour))
        assertEquals(CandleViewport.Fetch.Older(100 * hour), next)
        // Small holes of illiquid markets are not gaps between pages.
        assertNull(CandleViewport.nextFetch(times(1000, 1002, 1003), CandleInterval.H1, 1000 * hour, 1003 * hour, 900, 3, canLoadOlder = false))
    }
}
