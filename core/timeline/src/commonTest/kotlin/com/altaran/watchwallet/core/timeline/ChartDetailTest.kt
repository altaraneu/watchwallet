package com.altaran.watchwallet.core.timeline

import com.altaran.watchwallet.core.model.ChartRange
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ChartDetailTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val now = 1_800_000_000_000L / hour * hour + 17 * minute + 12_345

    private fun at(ms: Long) = Instant.fromEpochMilliseconds(ms)

    @Test
    fun resolutionFollowsTheVisibleSpan() {
        assertEquals(PriceResolution.MINUTE, ChartDetailPlanner.resolutionFor(1.hours.inWholeMilliseconds))
        assertEquals(PriceResolution.MINUTE, ChartDetailPlanner.resolutionFor(8.hours.inWholeMilliseconds))
        assertEquals(PriceResolution.HOUR, ChartDetailPlanner.resolutionFor(9.hours.inWholeMilliseconds))
        assertEquals(PriceResolution.HOUR, ChartDetailPlanner.resolutionFor(7.days.inWholeMilliseconds))
        assertEquals(PriceResolution.DAY, ChartDetailPlanner.resolutionFor(30.days.inWholeMilliseconds))
    }

    @Test
    fun resolutionHasHysteresisAtItsThresholds() {
        val nineHours = 9.hours.inWholeMilliseconds
        assertEquals(PriceResolution.MINUTE, ChartDetailPlanner.resolutionFor(nineHours, PriceResolution.MINUTE))
        assertEquals(PriceResolution.HOUR, ChartDetailPlanner.resolutionFor(nineHours, PriceResolution.HOUR))
        val fifteenDays = 15.days.inWholeMilliseconds
        assertEquals(PriceResolution.HOUR, ChartDetailPlanner.resolutionFor(fifteenDays, PriceResolution.HOUR))
        assertEquals(PriceResolution.DAY, ChartDetailPlanner.resolutionFor(fifteenDays, PriceResolution.DAY))
    }

    @Test
    fun oneHourViewLoadsTheLastFourHoursOfMinutes() {
        val detail = assertNotNull(ChartDetailPlanner.forRange(ChartRange.H1, now))
        assertEquals(PriceResolution.MINUTE, detail.resolution)
        assertTrue(detail.live)
        assertEquals(now, detail.to)
        assertTrue(kotlin.math.abs(4 * hour - (now - detail.from)) <= 1.minutes.inWholeMilliseconds)
    }

    @Test
    fun windowIsOneRequestAtMost() {
        listOf(1.hours, 8.hours, 10.hours, 7.days, 14.days, 17.days, 100.days, 299.days).forEach { span ->
            val detail = assertNotNull(ChartDetailPlanner.plan(null, now - span.inWholeMilliseconds, now, now))
            val bars = (detail.to - detail.from) / detail.resolution.stepMillis
            assertTrue(bars <= 1000, "$span has $bars bars")
        }
    }

    @Test
    fun aViewInsideTheWindowKeepsIt() {
        val first = assertNotNull(ChartDetailPlanner.plan(null, now - 30 * day, now - 30 * day + 6 * minute, now))
        assertFalse(first.live)
        // Panned a little: nothing to reload, nothing changes under the user.
        assertSame(first, ChartDetailPlanner.plan(first, now - 30 * day + 20 * minute, now - 30 * day + 26 * minute, now))
        assertEquals(PriceResolution.MINUTE, first.resolution)
        // At full zoom the window is still hours long, so panning stays at minutes for a long way.
        assertTrue(first.to - first.from >= 4 * hour)
    }

    @Test
    fun panningToTheEdgeMovesTheWindow() {
        val first = assertNotNull(ChartDetailPlanner.plan(null, now - 30 * day, now - 30 * day + hour, now))
        val moved = assertNotNull(ChartDetailPlanner.plan(first, first.from, first.from + hour, now))
        assertTrue(moved.from < first.from)
        assertEquals(PriceResolution.MINUTE, moved.resolution)
    }

    @Test
    fun repeatedReportsOfTheSameViewAreStable() {
        var detail: ChartDetail? = null
        val results = (0 until 5).map {
            detail = ChartDetailPlanner.plan(detail, now - 9 * hour, now - hour, now)
            detail
        }
        assertEquals(1, results.distinct().size)
    }

    @Test
    fun veryLongViewsNeedNoRefinement() {
        assertNull(ChartDetailPlanner.plan(null, now - 2000 * day, now, now))
        assertNull(ChartDetailPlanner.forRange(ChartRange.ALL, now))
        assertNull(ChartDetailPlanner.forRange(ChartRange.Y1, now))
        assertEquals(PriceResolution.DAY, ChartDetailPlanner.forRange(ChartRange.M1, now)?.resolution)
    }

    @Test
    fun gridIsAlignedAndALiveWindowEndsAtNow() {
        val detail = ChartDetail(PriceResolution.MINUTE, now - 10 * minute, now, live = true)
        val grid = ChartDetailPlanner.grid(detail, now)
        assertEquals(at(now), grid.last())
        assertTrue(grid.dropLast(1).all { it.toEpochMilliseconds() % minute == 0L })
        assertEquals(grid.sorted(), grid)
        val past = ChartDetail(PriceResolution.MINUTE, 10 * minute, 20 * minute, live = false)
        assertEquals((10L..20L).map { at(it * minute) }, ChartDetailPlanner.grid(past, now))
    }

    @Test
    fun liveGridStaysBoundedOverALongSession() {
        val detail = ChartDetail(PriceResolution.MINUTE, now - 100 * day, now, live = true)
        assertTrue(ChartDetailPlanner.grid(detail, now).size <= 1001)
    }

    @Test
    fun missingSpanCoversAllHolesInOneRequest() {
        val step = minute
        assertEquals(0L to 10 * step, ChartDetailPlanner.missingSpan(emptyList(), 0, 10 * step, step))
        assertNull(ChartDetailPlanner.missingSpan((0L..10L).map { it * step }, 0, 10 * step, step))
        // The newest candle only.
        assertEquals(9 * step to 10 * step, ChartDetailPlanner.missingSpan((0L..9L).map { it * step }, 0, 10 * step, step))
        // Older part missing.
        assertEquals(0L to 5 * step, ChartDetailPlanner.missingSpan((5L..10L).map { it * step }, 0, 10 * step, step))
        // A hole in the middle plus the tail.
        assertEquals(2 * step to 10 * step, ChartDetailPlanner.missingSpan(listOf(0L, 1L, 2L, 5L, 6L).map { it * step }, 0, 10 * step, step))
        // Not a whole step yet.
        assertNull(ChartDetailPlanner.missingSpan((0L..10L).map { it * step }, 0, 10 * step + 30_000, step))
    }

    @Test
    fun overlayUsesFinePricesWhereTheyExist() {
        val coarse = listOf(0L, 10L, 20L, 30L).map { TimedPrice(at(it), it.toDouble()) }
        val fine = listOf(11L, 12L, 25L).map { TimedPrice(at(it), 1000.0 + it) }
        val merged = ChartDetailPlanner.overlay(coarse, fine)
        assertEquals(listOf(0L, 10L, 11L, 12L, 25L, 30L), merged.map { it.time.toEpochMilliseconds() })
        assertSame(coarse, ChartDetailPlanner.overlay(coarse, emptyList()))
        assertSame(fine, ChartDetailPlanner.overlay(emptyList(), fine))
    }

    @Test
    fun mergePointsReplacesTheRefinedPartOnly() {
        val base = listOf(0L, 100L, 200L, 300L).map { TimedValue(at(it), 1.0) }
        val detail = listOf(150L, 160L, 170L).map { TimedValue(at(it), 2.0) }
        val merged = ChartDetailPlanner.mergePoints(base, detail)
        assertEquals(listOf(0L, 100L, 150L, 160L, 170L, 200L, 300L), merged.map { it.time.toEpochMilliseconds() })
        assertSame(base, ChartDetailPlanner.mergePoints(base, emptyList()))
    }

    @Test
    fun viewportChangesOnlyWhenTheViewNeedsIt() {
        val viewport = ChartViewport(ChartRange.H1) { now }
        val start = viewport.detail.value
        assertEquals(PriceResolution.MINUTE, start?.resolution)
        viewport.onVisible(now - hour, now)
        assertSame(start, viewport.detail.value)
        viewport.onVisible(now - 7 * day, now)
        assertEquals(PriceResolution.HOUR, viewport.detail.value?.resolution)
        viewport.show(ChartRange.ALL)
        assertNull(viewport.detail.value)
    }

    @Test
    fun alignedSamplesKeepTheEnds() {
        val samples = listOf(at(7), at(1007), at(2007), at(2999))
        assertEquals(listOf(at(7), at(1000), at(2000), at(2999)), Timeline.alignedSamples(samples, 1000))
    }

    private fun history(prices: List<TimedPrice>, current: Double?) = AssetHistory(
        currentBalance = BigInteger.fromInt(100),
        decimals = 2,
        deltas = emptyList(),
        prices = prices,
        currentPrice = current,
    )

    @Test
    fun aHeldAssetIsNotWorthlessWhileItsHistoryLoads() {
        val samples = listOf(at(1), at(2))
        assertEquals(listOf(5.0, 5.0), Timeline.portfolio(listOf(history(emptyList(), 5.0)), samples).map { it.value })
        assertEquals(listOf(0.0, 0.0), Timeline.portfolio(listOf(history(emptyList(), null)), samples).map { it.value })
    }

    @Test
    fun onlyAWindowEndingNowUsesTheLivePriceAtTheEnd() {
        val prices = listOf(TimedPrice(at(1), 2.0))
        val samples = listOf(at(1), at(2))
        assertEquals(listOf(2.0, 9.0), Timeline.portfolio(listOf(history(prices, 9.0)), samples).map { it.value })
        assertEquals(listOf(2.0, 2.0), Timeline.portfolio(listOf(history(prices, 9.0)), samples, lastIsLive = false).map { it.value })
    }
}
