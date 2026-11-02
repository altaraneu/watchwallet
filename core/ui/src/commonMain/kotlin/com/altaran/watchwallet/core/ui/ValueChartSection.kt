package com.altaran.watchwallet.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altaran.watchwallet.core.chart.AreaPoint
import com.altaran.watchwallet.core.chart.ChartNumberFormat
import com.altaran.watchwallet.core.chart.ChartSeries
import com.altaran.watchwallet.core.chart.ChartView
import com.altaran.watchwallet.core.chart.CrosshairValue
import com.altaran.watchwallet.core.chart.TradingChart
import com.altaran.watchwallet.core.designsystem.chart.ChartPoint
import com.altaran.watchwallet.core.designsystem.component.AmountText
import com.altaran.watchwallet.core.designsystem.component.ChangeText
import com.altaran.watchwallet.core.designsystem.component.LocalDiscreetMode
import com.altaran.watchwallet.core.designsystem.component.RangeSelector
import com.altaran.watchwallet.core.designsystem.component.rangeLabel
import com.altaran.watchwallet.core.designsystem.theme.WatchWalletTheme
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.ui.format.DateFormatter
import com.altaran.watchwallet.core.ui.format.MoneyFormatter
import com.altaran.watchwallet.core.ui.generated.resources.Res
import com.altaran.watchwallet.core.ui.generated.resources.chart_a11y_hidden
import com.altaran.watchwallet.core.ui.generated.resources.chart_a11y_summary
import com.altaran.watchwallet.core.ui.generated.resources.chart_current_value
import com.altaran.watchwallet.core.ui.generated.resources.chart_loading_prices
import com.altaran.watchwallet.core.ui.generated.resources.chart_no_data
import com.altaran.watchwallet.core.ui.generated.resources.chart_performance
import com.altaran.watchwallet.core.ui.generated.resources.chart_value_with_percent
import com.altaran.watchwallet.core.ui.generated.resources.chart_visible_range
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Price history is downloading somewhere in the app; explains a chart with nothing to draw yet. */
val LocalPriceHistoryLoading = compositionLocalOf { false }

/** Axis/crosshair number format for values in [currency], following the app language. */
fun chartFormatFor(currency: QuoteCurrency, maxDecimals: Int = currency.displayDecimals): ChartNumberFormat =
    ChartNumberFormat(
        localeTag = Locale.current.toLanguageTag(),
        currency = if (currency.kind == QuoteCurrency.Kind.FIAT) currency.code else null,
        suffix = if (currency.kind == QuoteCurrency.Kind.CRYPTO) currency.code else null,
        minDecimals = minOf(currency.displayDecimals, maxDecimals),
        maxDecimals = maxDecimals,
    )

/**
 * Header value + change since range start + interactive area chart (pan, pinch zoom, crosshair)
 * + range chips. While the user touches the chart, the header shows the value and date under
 * the finger and the change from the start of the range to that point.
 *
 * The chart draws whatever is available and refines as better prices arrive; nothing here waits
 * for them. Pan and zoom never change [range]: they only light the chip closest to what is visible
 * and report the view through [onVisibleWindow]. Tapping a chip goes to its range, also if it is
 * the selected one and the user has moved away from it.
 *
 * @param points `null` while loading.
 * @param onVisibleWindow the view (epoch ms) once pan or zoom have settled.
 */
@Composable
fun ValueChartSection(
    label: String,
    currentValue: Double?,
    points: List<ChartPoint>?,
    range: ChartRange,
    onRangeChange: (ChartRange) -> Unit,
    format: (Double) -> String,
    formatChange: (Double) -> String,
    numberFormat: ChartNumberFormat,
    modifier: Modifier = Modifier,
    logScale: Boolean = false,
    markerTimes: List<Long> = emptyList(),
    secondaryLine: String? = null,
    chartHeight: Dp = 260.dp,
    ranges: List<ChartRange> = ChartRange.entries,
    /** Extra identity of what the chart shows (for example a mode); a change resets pan and zoom. */
    windowKey: String = "",
    onVisibleWindow: (from: Long, to: Long) -> Unit = { _, _ -> },
) {
    // Every tap on a chip goes back to its range, even when the range itself does not change.
    var chipTaps by remember { mutableStateOf(0) }
    // Pan, zoom and the tapped point survive data refreshes and reset with the window, in sync
    // with the chart page (which reports the kept selection and window after every refresh).
    val chartWindowKey = "${range.name}|$windowKey|$chipTaps"
    var crosshair by remember(chartWindowKey) { mutableStateOf<CrosshairValue?>(null) }
    // Visible window after pan/zoom (epoch ms of the first and last visible point).
    var visible by remember(chartWindowKey) { mutableStateOf<Pair<Long, Long>?>(null) }
    // The same window once the gesture has settled: the chip does not jitter under a moving finger.
    var settled by remember(chartWindowKey) { mutableStateOf<Pair<Long, Long>?>(null) }
    val currentOnVisibleWindow by rememberUpdatedState(onVisibleWindow)
    LaunchedEffect(visible) {
        val window = visible ?: return@LaunchedEffect
        delay(SETTLE_MS)
        settled = window
        currentOnVisibleWindow(window.first, window.second)
    }
    // While loading the chart is not shown: a new chart starts without selection or pan.
    val loading = points == null
    LaunchedEffect(loading) {
        if (loading) {
            crosshair = null
            visible = null
            settled = null
        }
    }
    // Points cover the whole history (for panning back); the change refers to the selected range.
    val windowStart = remember(range, points) { rangeStartMillis(range) }
    val chip = remember(settled, range, ranges, points) { chipFor(settled, range, ranges, points) }
    val header = headerValues(points, windowStart, visible, currentValue)
    val shown = crosshair?.value ?: header.end
    val start = header.start
    val end = crosshair?.value ?: header.end
    // A selected point is in the past: say what the value is now, in the same place every time.
    val current = currentValue?.takeIf { crosshair != null }?.let { stringResource(Res.string.chart_current_value, format(it)) }

    Column(modifier.fillMaxWidth()) {
        ValueHeader(
            // Panned back: the header follows the right edge of what is visible, not today.
            label = crosshair?.let { DateFormatter.dateTime(Instant.fromEpochSeconds(it.time)) }
                ?: header.endTime?.let { DateFormatter.dateTime(Instant.fromEpochMilliseconds(it)) }
                ?: label,
            value = shown?.let(format),
            change = if (start != null && end != null) end - start else null,
            changePercent = if (start != null && end != null && start > 0) (end - start) / start else null,
            formatChange = formatChange,
            rangeText = if (header.panned || chip != range) stringResource(Res.string.chart_visible_range) else rangeLabel(range),
            secondaryLine = current ?: secondaryLine,
            reserveSecondaryLine = true,
        )
        PerformanceLine(header, crosshair, points, formatChange)
        ChartBody(
            points = points,
            start = start,
            end = end,
            visibleFrom = windowStart?.let { it / MILLIS },
            range = range,
            format = format,
            formatChange = formatChange,
            numberFormat = numberFormat,
            logScale = logScale,
            markerTimes = markerTimes,
            chartHeight = chartHeight,
            windowKey = chartWindowKey,
            onCrosshair = { crosshair = it },
            onVisibleRange = { view -> visible = view.from * MILLIS to view.to * MILLIS },
        )
        RangeSelector(
            selected = chip,
            onSelect = {
                chipTaps++
                onRangeChange(it)
            },
            ranges = ranges,
        )
    }
}

/** How long the view must rest before it counts as settled (chips, loading better prices). */
private const val SETTLE_MS = 300L

/** The chip for what is visible: the selected one until the user has moved the chart. */
private fun chipFor(settled: Pair<Long, Long>?, selected: ChartRange, ranges: List<ChartRange>, points: List<ChartPoint>?): ChartRange? {
    val (from, to) = settled ?: return selected
    val now = kotlin.time.Clock.System.now()
    return ChartRange.chipFor((to - from).milliseconds, selected, ranges) { range ->
        when (range) {
            ChartRange.ALL -> points?.firstOrNull()?.let { (now.toEpochMilliseconds() - it.timeMillis).milliseconds }
            ChartRange.YTD -> rangeStartMillis(range)?.let { (now.toEpochMilliseconds() - it).milliseconds }
            else -> range.span
        }
    }
}

/** Big value with label above and the signed change below. Shared by all chart screens. */
@Composable
fun ValueHeader(
    label: String,
    value: String?,
    change: Double?,
    changePercent: Double?,
    formatChange: (Double) -> String,
    rangeText: String?,
    secondaryLine: String? = null,
    /** Keeps the line's height when there is nothing to show, so the chart below never moves. */
    reserveSecondaryLine: Boolean = false,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmountText(value ?: "—", style = MaterialTheme.typography.displaySmall)
        if (change != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val changeText = formatChange(change)
                ChangeText(
                    formatted = changePercent?.let { stringResource(Res.string.chart_value_with_percent, changeText, MoneyFormatter.formatPercentChange(it)) }
                        ?: changeText,
                    direction = change.compareTo(0.0),
                    discreetAware = true,
                )
                if (rangeText != null) {
                    Text(rangeText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (secondaryLine != null) {
            AmountText(secondaryLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (reserveSecondaryLine) {
            Text("", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Start of [range] in epoch milliseconds, or `null` for the whole history. */
private fun rangeStartMillis(range: ChartRange): Long? {
    val now = kotlin.time.Clock.System.now()
    return when (range) {
        ChartRange.ALL -> {
            null
        }

        ChartRange.YTD -> {
            val zone = TimeZone.currentSystemDefault()
            LocalDate(now.toLocalDateTime(zone).year, 1, 1).atStartOfDayIn(zone).toEpochMilliseconds()
        }

        else -> {
            range.span?.let { (now - it).toEpochMilliseconds() }
        }
    }
}

/** Screen reader summary of a value chart (no amounts in discreet mode). */
@Composable
private fun chartSummary(range: String, first: Double, last: Double, format: (Double) -> String, formatChange: (Double) -> String): String =
    if (LocalDiscreetMode.current) {
        stringResource(Res.string.chart_a11y_hidden, range)
    } else {
        stringResource(Res.string.chart_a11y_summary, range, format(first), format(last), formatChange(last - first))
    }

@Composable
private fun ChartBody(
    points: List<ChartPoint>?,
    start: Double?,
    end: Double?,
    visibleFrom: Long?,
    range: ChartRange,
    format: (Double) -> String,
    formatChange: (Double) -> String,
    numberFormat: ChartNumberFormat,
    logScale: Boolean,
    markerTimes: List<Long>,
    chartHeight: Dp,
    windowKey: String,
    onCrosshair: (CrosshairValue?) -> Unit,
    onVisibleRange: (ChartView) -> Unit,
) {
    val series = remember(points) { points?.let { list -> ChartSeries.Area(list.map { AreaPoint(it.timeMillis / MILLIS, it.value) }) } }
    val markerSeconds = remember(markerTimes) { markerTimes.map { it / MILLIS } }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(chartHeight), contentAlignment = Alignment.Center) {
        when {
            points == null -> {
                LinearProgressIndicator(Modifier.padding(horizontal = 48.dp).fillMaxWidth())
            }

            points.size < 2 || points.all { it.value == 0.0 } -> {
                // Nothing to draw: the only moment a hint about loading prices is needed.
                Text(
                    stringResource(if (LocalPriceHistoryLoading.current) Res.string.chart_loading_prices else Res.string.chart_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            series != null -> {
                val colors = WatchWalletTheme.extendedColors
                val first = start ?: points.first().value
                val last = end ?: points.last().value
                val summary = chartSummary(rangeLabel(range), first, last, format, formatChange)
                TradingChart(
                    series = series,
                    format = numberFormat,
                    height = chartHeight,
                    logScale = logScale,
                    hideValues = LocalDiscreetMode.current,
                    markerTimes = markerSeconds,
                    // The colour says the same as the change in the header.
                    lineColor = if (last >= first) colors.positive else colors.negative,
                    onCrosshair = onCrosshair,
                    onVisibleRange = onVisibleRange,
                    contentDescription = summary,
                    visibleFrom = visibleFrom,
                    windowKey = windowKey,
                )
            }
        }
    }
}

private const val MILLIS = 1000L

/** What the header shows without a finger on the chart. */
private class HeaderValues(
    val start: Double?,
    val end: Double?,
    val endTime: Long?,
    val panned: Boolean,
    /** Net deposits between the start and the end of the window. */
    val flowDelta: Double = 0.0,
    val startFlow: Double = 0.0,
)

/**
 * Start and end of the change shown above the chart. When the visible window ends before the
 * latest point (panned back), both come from the window, so the numbers match what is drawn.
 * The current value stays live whenever the window reaches the present.
 */
private fun headerValues(points: List<ChartPoint>?, windowStart: Long?, visible: Pair<Long, Long>?, current: Double?): HeaderValues {
    if (points.isNullOrEmpty()) return HeaderValues(null, current, null, false)
    if (visible == null) {
        // The value when the range began: the last point before it, while the data is still coarse.
        val startPoint = windowStart?.let { start -> points.getOrNull(lastIndexAtOrBefore(points, start)) } ?: points.first()
        return HeaderValues(startPoint.value, current ?: points.last().value, null, false, points.last().flow - startPoint.flow, startPoint.flow)
    }
    val (from, to) = visible
    val first = points[(lastIndexAtOrBefore(points, from - 1) + 1).coerceAtMost(points.lastIndex)]
    val lastIndex = lastIndexAtOrBefore(points, to).coerceAtLeast(0)
    val last = points[lastIndex]
    // The window edge maps to a bar index with rounding; only a real pan back (more than one point
    // before the end) counts as "panned".
    val panned = lastIndex < points.lastIndex - 1
    return if (panned) {
        HeaderValues(first.value, last.value, last.timeMillis, true, last.flow - first.flow, first.flow)
    } else {
        HeaderValues(first.value, current ?: last.value, null, false, points.last().flow - first.flow, first.flow)
    }
}

/** Index of the last point at or before [time], or -1; this runs on every frame of a gesture. */
private fun lastIndexAtOrBefore(points: List<ChartPoint>, time: Long): Int {
    var low = 0
    var high = points.size
    while (low < high) {
        val mid = (low + high) / 2
        if (points[mid].timeMillis <= time) low = mid + 1 else high = mid
    }
    return low - 1
}

/** Minimum net deposits (in the chart currency) for showing the performance line. */
private const val MIN_FLOW = 0.005

/**
 * "Excluding deposits and withdrawals": the value change minus what was transferred in or out,
 * so a deposit does not look like a gain. Relative to the starting value plus new money.
 */
@Composable
private fun PerformanceLine(header: HeaderValues, crosshair: CrosshairValue?, points: List<ChartPoint>?, formatChange: (Double) -> String) {
    // Whether a chart has deposits at all does not depend on the window: its line keeps its place
    // while panning, so that the chart below does not move by a line of text.
    val reserve = remember(points) { points?.any { kotlin.math.abs(it.flow) >= MIN_FLOW } == true }
    if (!reserve) return
    val text = performanceText(header, crosshair, points, formatChange)
    if (text == null) {
        Text("", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
    } else {
        AmountText(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun performanceText(header: HeaderValues, crosshair: CrosshairValue?, points: List<ChartPoint>?, formatChange: (Double) -> String): String? {
    val start = header.start ?: return null
    val crossPoint = crosshair?.let { c -> points?.let { it.getOrNull(lastIndexAtOrBefore(it, c.time * MILLIS)) } }
    val end = crosshair?.value ?: header.end ?: return null
    val flow = crossPoint?.let { it.flow - header.startFlow } ?: header.flowDelta
    if (kotlin.math.abs(flow) < MIN_FLOW) return null
    val performance = end - start - flow
    val base = start + flow.coerceAtLeast(0.0)
    val percent = if (base > 0) MoneyFormatter.formatPercentChange(performance / base) else null
    val amount = formatChange(performance)
    val change = percent?.let { stringResource(Res.string.chart_value_with_percent, amount, it) } ?: amount
    return stringResource(Res.string.chart_performance, change)
}
