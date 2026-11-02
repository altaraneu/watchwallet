package com.altaran.watchwallet.core.timeline

import com.altaran.watchwallet.core.model.ChartRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Sampling and price resolution of the part of a chart the user is looking at. */
enum class PriceResolution(val stepMillis: Long) {
    MINUTE(60_000L),
    HOUR(3_600_000L),
    DAY(86_400_000L),
}

/**
 * The refined part of a chart: bars every [resolution] step between [from] and [to] (epoch ms),
 * coarse bars everywhere else. [live] windows follow the present and end at the live value.
 */
data class ChartDetail(val resolution: PriceResolution, val from: Long, val to: Long, val live: Boolean)

/**
 * Chooses the refined window for what is visible, like a map choosing tiles for its zoom level.
 * The window is larger than the view, so panning stays at the same scale between two refinements.
 */
object ChartDetailPlanner {
    /** A view up to this long gets one bar per minute. */
    val MINUTE_MAX_SPAN_MS = 8.hours.inWholeMilliseconds

    /** A view up to this long gets one bar per hour; anything longer one per day. */
    val HOUR_MAX_SPAN_MS = 14.days.inWholeMilliseconds

    /** Above this length the regular daily samples of the whole history are good enough. */
    val DETAIL_MAX_SPAN_MS = 300.days.inWholeMilliseconds

    /** Binance serves 1000 candles per request; one window is one request per asset. */
    const val MAX_BARS = 999

    private const val LEAVE_FACTOR = 1.25
    private const val WINDOW_FACTOR = 3
    private const val EDGE_DIVISOR = 4
    private val MIN_WINDOW_MS = mapOf(
        PriceResolution.MINUTE to 4.hours.inWholeMilliseconds,
        PriceResolution.HOUR to 2.days.inWholeMilliseconds,
        PriceResolution.DAY to 90.days.inWholeMilliseconds,
    )

    /** Finest resolution for a view of [spanMs]; [current] only changes clearly past a threshold. */
    fun resolutionFor(spanMs: Long, current: PriceResolution? = null): PriceResolution {
        val minuteLimit = if (current == PriceResolution.MINUTE) (MINUTE_MAX_SPAN_MS * LEAVE_FACTOR).toLong() else MINUTE_MAX_SPAN_MS
        val hourLimit = if (current == PriceResolution.DAY) HOUR_MAX_SPAN_MS else (HOUR_MAX_SPAN_MS * LEAVE_FACTOR).toLong()
        return when {
            spanMs <= minuteLimit -> PriceResolution.MINUTE
            spanMs <= hourLimit -> PriceResolution.HOUR
            else -> PriceResolution.DAY
        }
    }

    /** The window for a view of [from]..[to]: [current] while it still serves, `null` for very long views. */
    fun plan(current: ChartDetail?, from: Long, to: Long, now: Long): ChartDetail? {
        val span = (to - from).coerceAtLeast(1)
        if (span > DETAIL_MAX_SPAN_MS) return null
        val resolution = resolutionFor(span, current?.resolution)
        if (current != null && current.resolution == resolution && serves(current, from, to, span)) return current
        val step = resolution.stepMillis
        val size = (span * WINDOW_FACTOR).coerceIn(MIN_WINDOW_MS.getValue(resolution), MAX_BARS * step)
        val start = from + span / 2 - size / 2
        val end = start + size
        val live = end >= now - step
        val alignedTo = if (live) now else end / step * step
        val alignedFrom = (if (live) now - size else start) / step * step
        return ChartDetail(resolution, alignedFrom, alignedTo, live)
    }

    private fun serves(window: ChartDetail, from: Long, to: Long, span: Long): Boolean {
        val margin = span / EDGE_DIVISOR
        return from - window.from >= margin && (window.live || window.to - to >= margin)
    }

    /** Sample times of the window, oldest first; a live window ends exactly at [now]. */
    fun grid(detail: ChartDetail, now: Long): List<Instant> {
        val step = detail.resolution.stepMillis
        val end = if (detail.live) now else detail.to
        val first = maxOf(detail.from / step, end / step - MAX_BARS) * step
        val result = ArrayList<Instant>(((end - first) / step).toInt() + 2)
        var time = first
        while (time <= end) {
            result += Instant.fromEpochMilliseconds(time)
            time += step
        }
        if (detail.live && result.lastOrNull()?.toEpochMilliseconds() != now) result += Instant.fromEpochMilliseconds(now)
        return result
    }

    /** Span still to fetch: from the first hole to the last one, or `null` when [from]..[to] is complete. */
    fun missingSpan(times: List<Long>, from: Long, to: Long, step: Long): Pair<Long, Long>? {
        val tolerance = step + step / 2
        if (times.isEmpty()) return if (to - from >= step) from to to else null
        var start: Long? = if (times.first() - from >= tolerance) from else null
        var end: Long? = if (start != null) times.first() else null
        for (i in 0 until times.lastIndex) {
            if (times[i + 1] - times[i] >= tolerance) {
                if (start == null) start = times[i]
                end = times[i + 1]
            }
        }
        if (to - times.last() >= step) {
            if (start == null) start = times.last()
            end = to
        }
        return start?.let { it to requireNotNull(end) }
    }

    /** [coarse] with [fine] taking over wherever it has data, so a curve never zigzags between sources. */
    fun overlay(coarse: List<TimedPrice>, fine: List<TimedPrice>): List<TimedPrice> {
        if (fine.isEmpty()) return coarse
        if (coarse.isEmpty()) return fine
        val first = fine.first().time
        val last = fine.last().time
        val result = ArrayList<TimedPrice>(coarse.size + fine.size)
        coarse.forEach { if (it.time < first) result += it }
        result += fine
        coarse.forEach { if (it.time > last) result += it }
        return result
    }

    /** Coarse points outside the refined part plus the refined part itself, in time order. */
    fun mergePoints(base: List<TimedValue>, detail: List<TimedValue>): List<TimedValue> {
        if (detail.isEmpty()) return base
        val first = detail.first().time
        val last = detail.last().time
        val result = ArrayList<TimedValue>(base.size + detail.size)
        base.forEach { if (it.time < first) result += it }
        result += detail
        base.forEach { if (it.time > last) result += it }
        return result
    }

    /** The window a range starts with, before the chart reports what it shows. */
    fun forRange(range: ChartRange, now: Long): ChartDetail? {
        val span = range.span?.inWholeMilliseconds ?: return null
        return plan(null, now - span, now, now)
    }
}

/**
 * State of one chart's refinement. The chip selects the starting window, then pan and zoom reports
 * move it. Reports never change the selected range, so there is no reset loop between the two.
 */
class ChartViewport(range: ChartRange, private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val state = MutableStateFlow(ChartDetailPlanner.forRange(range, now()))

    val detail: StateFlow<ChartDetail?> = state.asStateFlow()

    fun show(range: ChartRange) {
        state.value = ChartDetailPlanner.forRange(range, now())
    }

    /** The view (epoch ms) after pan or zoom settled. */
    fun onVisible(from: Long, to: Long) {
        state.update { ChartDetailPlanner.plan(it, from, to, now()) }
    }
}
