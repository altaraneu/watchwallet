package com.altaran.watchwallet.core.timeline

import com.altaran.watchwallet.core.model.ChartRange
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.pow
import kotlin.time.Instant

/** A balance change at a point in time, in the asset's smallest unit. */
data class TimedDelta(val time: Instant, val delta: BigInteger)

data class TimedPrice(val time: Instant, val price: Double)

/** A value at a time; [flow] = cumulative net deposits (in − out, valued at transfer time) up to then. */
data class TimedValue(val time: Instant, val value: Double, val flow: Double = 0.0)

/** Everything needed to chart one asset of one or more wallets. */
data class AssetHistory(
    val currentBalance: BigInteger,
    val decimals: Int,
    val deltas: List<TimedDelta>,
    /** Price history sorted by time; may be empty (values then use [currentPrice], or 0 without one). */
    val prices: List<TimedPrice>,
    /** Latest price, used for the final sample so the chart ends at the live value. */
    val currentPrice: Double?,
    /** The balance did not exist up to this time; `null` = as old as the rebuilt history says. */
    val startsAt: Instant? = null,
)

/**
 * Reconstructs balances over time from transaction history and values them with historical
 * prices.
 *
 * Balances are rebuilt **backwards from the current balance**: `balance(t) = current − Σ deltas
 * after t`. This keeps the latest point exact even if old history is incomplete (truncated
 * history, missing internal transactions): errors only affect the distant past.
 */
object Timeline {
    /** Sample instants for a range, oldest first, always ending at [now]. */
    fun sampleTimes(range: ChartRange, now: Instant, firstActivity: Instant?, timeZone: TimeZone = TimeZone.currentSystemDefault()): List<Instant> {
        val start = when (range) {
            ChartRange.YTD -> {
                val year = now.toLocalDateTime(timeZone).year
                kotlinx.datetime.LocalDateTime(year, 1, 1, 0, 0).toInstant(timeZone)
            }

            ChartRange.ALL -> {
                firstActivity ?: (now - DEFAULT_ALL_SPAN)
            }

            else -> {
                now - requireNotNull(range.span)
            }
        }
        val step = range.step
        // Long ALL ranges: keep the number of points bounded for smooth rendering.
        val count = ((now - start) / step).toLong().coerceAtLeast(1)
        val effectiveStep = if (count > MAX_POINTS) (now - start) / MAX_POINTS.toInt() else step
        val times = generateSequence(start) { it + effectiveStep }.takeWhile { it < now }.toMutableList()
        times += now
        return times
    }

    /**
     * Pins sample times to multiples of [stepMillis], so bars keep their time across recomputations
     * (a tapped bar stays selected). The first sample (the start of the history) and the last one
     * (now) stay exact.
     */
    fun alignedSamples(samples: List<Instant>, stepMillis: Long): List<Instant> {
        if (samples.size < 2) return samples
        val result = ArrayList<Instant>(samples.size)
        result += samples.first()
        for (i in 1 until samples.lastIndex) {
            val aligned = Instant.fromEpochMilliseconds(samples[i].toEpochMilliseconds() / stepMillis * stepMillis)
            if (aligned > result.last() && aligned < samples.last()) result += aligned
        }
        result += samples.last()
        return result
    }

    /**
     * Balance (as a decimal number) of one asset at every sample time.
     *
     * @param lastIsLive the last sample is "now", where the current balance always counts; false
     * for a window that ends in the past, which may end before [AssetHistory.startsAt].
     */
    fun balances(history: AssetHistory, samples: List<Instant>, lastIsLive: Boolean = true): List<Double> = balanceArray(history, samples, lastIsLive).asList()

    private fun balanceArray(history: AssetHistory, samples: List<Instant>, lastIsLive: Boolean): DoubleArray {
        // Doubles are exact enough for a chart and avoid allocating a decimal per sample (charts
        // have thousands of samples over years of history).
        val scale = 10.0.pow(history.decimals)
        val sortedDeltas = history.deltas.sortedByDescending { it.time }
        val deltaValues = DoubleArray(sortedDeltas.size) { sortedDeltas[it].delta.doubleValue(exactRequired = false) / scale }
        val result = DoubleArray(samples.size)
        var running = history.currentBalance.doubleValue(exactRequired = false) / scale
        var index = 0
        // Walk samples from newest to oldest, removing deltas that happened after each sample.
        for (i in samples.indices.reversed()) {
            val sample = samples[i]
            while (index < sortedDeltas.size && sortedDeltas[index].time > sample) {
                running -= deltaValues[index]
                index++
            }
            val notYet = history.startsAt != null && sample <= history.startsAt && !(lastIsLive && i == samples.lastIndex)
            result[i] = if (notYet) 0.0 else running.coerceAtLeast(0.0)
        }
        return result
    }

    /** Price at [time]: the last known price at or before it (or the first one for earlier times). */
    fun priceAt(prices: List<TimedPrice>, time: Instant): Double? {
        if (prices.isEmpty()) return null
        var low = 0
        var high = prices.size - 1
        if (time < prices[0].time) return prices[0].price
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (prices[mid].time <= time) low = mid else high = mid - 1
        }
        return prices[low].price
    }

    /**
     * Value of one asset over time. Without any price history yet the current price is the nearest
     * thing available: a held asset does not count as worthless while its history is loading.
     */
    fun values(history: AssetHistory, samples: List<Instant>): List<TimedValue> {
        val totals = DoubleArray(samples.size)
        addValues(history, samples, lastIsLive = true, totals)
        return samples.mapIndexed { i, time -> TimedValue(time, totals[i]) }
    }

    /**
     * Total value over time across assets.
     *
     * @param lastIsLive the last sample is "now" and uses the live price; false for a window that
     * ends in the past.
     */
    fun portfolio(histories: List<AssetHistory>, samples: List<Instant>, lastIsLive: Boolean = true): List<TimedValue> {
        val totals = DoubleArray(samples.size)
        histories.forEach { addValues(it, samples, lastIsLive, totals) }
        return samples.mapIndexed { i, time -> TimedValue(time, totals[i]) }
    }

    /** Adds one asset to [totals]: samples and prices are both sorted, so one pass is enough. */
    private fun addValues(history: AssetHistory, samples: List<Instant>, lastIsLive: Boolean, totals: DoubleArray) {
        val balances = balanceArray(history, samples, lastIsLive)
        val prices = history.prices
        var index = 0
        for (i in samples.indices) {
            val balance = balances[i]
            if (balance == 0.0) continue
            val live = if (lastIsLive && i == samples.lastIndex) history.currentPrice else null
            val price = live ?: if (prices.isEmpty()) {
                history.currentPrice ?: 0.0
            } else {
                val time = samples[i]
                while (index + 1 < prices.size && prices[index + 1].time <= time) index++
                prices[index].price
            }
            totals[i] += balance * price
        }
    }

    /**
     * Reduces a long series to at most [maxPoints] for rendering, keeping the first and last
     * points and the extremes of each bucket so peaks are not lost.
     */
    fun downsample(points: List<TimedValue>, maxPoints: Int = 300): List<TimedValue> {
        if (points.size <= maxPoints) return points
        val bucketSize = (points.size - 2).toDouble() / (maxPoints / 2 - 1)
        val result = mutableListOf(points.first())
        var start = 1.0
        while (start < points.size - 1) {
            val end = minOf((start + bucketSize).toInt(), points.size - 1)
            val bucket = points.subList(start.toInt(), maxOf(end, start.toInt() + 1))
            val min = bucket.minBy { it.value }
            val max = bucket.maxBy { it.value }
            if (min.time <= max.time) result += listOf(min, max).distinct() else result += listOf(max, min).distinct()
            start += bucketSize
        }
        result += points.last()
        return result
    }

    private val DEFAULT_ALL_SPAN = kotlin.time.Duration.parse("365d")
    private const val MAX_POINTS = 1_500L
}
