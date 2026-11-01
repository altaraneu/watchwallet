package com.altaran.watchwallet.core.timeline

import com.altaran.watchwallet.core.model.ChartRange
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class TimelineTest {
    private val day0 = Instant.parse("2024-01-01T00:00:00Z")
    private fun day(n: Int) = day0 + n.days
    private fun sats(n: Long) = BigInteger.fromLong(n)

    // Received 1.0 BTC on day 1, sent 0.3 + 0.001 fee on day 3, self-transfer fee 0.0001 on day 4.
    private val btc = AssetHistory(
        currentBalance = sats(69_890_000),
        decimals = 8,
        deltas = listOf(
            TimedDelta(day(1), sats(100_000_000)),
            TimedDelta(day(3), sats(-30_100_000)),
            TimedDelta(day(4), sats(-10_000)),
        ),
        prices = listOf(TimedPrice(day(0), 100.0), TimedPrice(day(2), 200.0), TimedPrice(day(4), 300.0)),
        currentPrice = 310.0,
    )

    @Test
    fun balancesAreRebuiltBackwardsFromCurrentBalance() {
        val samples = (0..5).map(::day)
        val balances = Timeline.balances(btc, samples)
        assertEquals(listOf(0.0, 1.0, 1.0, 0.699, 0.6989, 0.6989), balances.map { (it * 10_000).toLong() / 10_000.0 })
    }

    @Test
    fun valuesUseThePriceValidAtEachSampleAndLivePriceAtTheEnd() {
        val samples = listOf(day(1), day(2), day(5))
        val values = Timeline.values(btc, samples).map { it.value }
        assertEquals(100.0, values[0], 1e-9) // 1 BTC × 100
        assertEquals(200.0, values[1], 1e-9) // 1 BTC × 200
        assertEquals(0.6989 * 310.0, values[2], 1e-9) // live price
    }

    @Test
    fun portfolioSumsAssetsAndUnpricedAssetsCountAsZero() {
        val unpriced = AssetHistory(BigInteger.fromLong(5), 0, emptyList(), emptyList(), null)
        val samples = listOf(day(2), day(5))
        assertEquals(200.0, Timeline.portfolio(listOf(btc, unpriced), samples).first().value, 1e-9)
    }

    @Test
    fun priceLookup() {
        assertEquals(100.0, Timeline.priceAt(btc.prices, day(-3)), "before first point → first price")
        assertEquals(200.0, Timeline.priceAt(btc.prices, day(3)))
        assertEquals(300.0, Timeline.priceAt(btc.prices, day(30)))
    }

    @Test
    fun sampleTimesEndAtNowAndAreBounded() {
        val now = day(1000)
        val samples = Timeline.sampleTimes(ChartRange.ALL, now, firstActivity = day(0))
        assertEquals(now, samples.last())
        assertTrue(samples.size <= 1_501)
        assertEquals(25, Timeline.sampleTimes(ChartRange.H24, now, null).size)
    }

    @Test
    fun downsampleKeepsEndsAndExtremes() {
        val points = (0 until 10_000).map { TimedValue(day0 + (it * 60).let { s -> kotlin.time.Duration.parse("${s}s") }, if (it == 5_000) 999.0 else 1.0) }
        val reduced = Timeline.downsample(points, 200)
        assertTrue(reduced.size <= 202)
        assertEquals(points.first(), reduced.first())
        assertEquals(points.last(), reduced.last())
        assertTrue(reduced.any { it.value == 999.0 }, "peak preserved")
    }
}
