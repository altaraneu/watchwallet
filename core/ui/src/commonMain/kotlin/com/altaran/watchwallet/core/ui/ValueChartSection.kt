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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altaran.watchwallet.core.chart.AreaPoint
import com.altaran.watchwallet.core.chart.ChartNumberFormat
import com.altaran.watchwallet.core.chart.ChartSeries
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
import com.altaran.watchwallet.core.ui.generated.resources.chart_loading_prices
import com.altaran.watchwallet.core.ui.generated.resources.chart_no_data
import com.altaran.watchwallet.core.ui.generated.resources.chart_visible_range
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Price history is downloading somewhere in the app (provided at the app root). */
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
 * @param points `null` while loading.
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
) {
    var crosshair by remember(points) { mutableStateOf<CrosshairValue?>(null) }
    // Visible window after pan/zoom (epoch ms of the first and last visible point).
    var visible by remember(range) { mutableStateOf<Pair<Long, Long>?>(null) }
    // Points cover the whole history (for panning back); the change refers to the selected range.
    val windowStart = remember(range, points) { rangeStartMillis(range) }
    val inRange = points?.filter { windowStart == null || it.timeMillis >= windowStart }
    val header = headerValues(points, inRange, visible, currentValue)
    val shown = crosshair?.value ?: header.end
    val start = header.start
    val end = crosshair?.value ?: header.end

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
            rangeText = if (header.panned) stringResource(Res.string.chart_visible_range) else rangeLabel(range),
            secondaryLine = secondaryLine,
        )
        LoadingHint(visible = LocalPriceHistoryLoading.current && points != null)
        ChartBody(
            points = points,
            inRange = inRange,
            range = range,
            format = format,
            formatChange = formatChange,
            numberFormat = numberFormat,
            logScale = logScale,
            markerTimes = markerTimes,
            chartHeight = chartHeight,
            onCrosshair = { crosshair = it },
            onVisibleRange = { from, to -> visible = from * MILLIS to to * MILLIS },
        )
        RangeSelector(selected = range, onSelect = onRangeChange, ranges = ranges)
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
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmountText(value ?: "—", style = MaterialTheme.typography.displaySmall)
        if (change != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChangeText(
                    formatted = formatChange(change) + (changePercent?.let { " (" + MoneyFormatter.formatPercentChange(it) + ")" } ?: ""),
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

/** Shown while price history downloads: the chart may still be incomplete. */
@Composable
private fun LoadingHint(visible: Boolean) {
    if (!visible) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            stringResource(Res.string.chart_loading_prices),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ChartBody(
    points: List<ChartPoint>?,
    inRange: List<ChartPoint>?,
    range: ChartRange,
    format: (Double) -> String,
    formatChange: (Double) -> String,
    numberFormat: ChartNumberFormat,
    logScale: Boolean,
    markerTimes: List<Long>,
    chartHeight: Dp,
    onCrosshair: (CrosshairValue?) -> Unit,
    onVisibleRange: (Long, Long) -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(chartHeight), contentAlignment = Alignment.Center) {
        when {
            points == null -> {
                LinearProgressIndicator(Modifier.padding(horizontal = 48.dp).fillMaxWidth())
            }

            points.size < 2 || points.all { it.value == 0.0 } -> {
                Text(
                    stringResource(Res.string.chart_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                val colors = WatchWalletTheme.extendedColors
                val first = (inRange?.firstOrNull() ?: points.first()).value
                val last = points.last().value
                val summary = chartSummary(rangeLabel(range).orEmpty(), first, last, format, formatChange)
                TradingChart(
                    series = ChartSeries.Area(points.map { AreaPoint(it.timeMillis / 1000, it.value) }),
                    format = numberFormat,
                    height = chartHeight,
                    intraday = range.needsHourlyPrices,
                    logScale = logScale,
                    hideValues = LocalDiscreetMode.current,
                    markerTimes = markerTimes.map { it / 1000 },
                    lineColor = if (points.last().value >= points.first().value) colors.positive else colors.negative,
                    onCrosshair = onCrosshair,
                    onVisibleRange = onVisibleRange,
                    contentDescription = summary,
                    visibleBars = inRange?.size?.takeIf { it in 2 until points.size } ?: 0,
                )
            }
        }
    }
}

private const val MILLIS = 1000L

/** What the header shows without a finger on the chart. */
private class HeaderValues(val start: Double?, val end: Double?, val endTime: Long?, val panned: Boolean)

/**
 * Start and end of the change shown above the chart. When the visible window ends before the
 * latest point (panned back), both come from the window, so the numbers match what is drawn.
 */
private fun headerValues(points: List<ChartPoint>?, inRange: List<ChartPoint>?, visible: Pair<Long, Long>?, current: Double?): HeaderValues {
    val defaultStart = inRange?.firstOrNull()?.value ?: points?.firstOrNull()?.value
    if (points.isNullOrEmpty() || visible == null) return HeaderValues(defaultStart, current ?: points?.lastOrNull()?.value, null, false)
    val (from, to) = visible
    val first = points.firstOrNull { it.timeMillis >= from } ?: points.first()
    val last = points.lastOrNull { it.timeMillis <= to } ?: points.last()
    val panned = last.timeMillis < points.last().timeMillis
    return if (panned) {
        HeaderValues(first.value, last.value, last.timeMillis, true)
    } else {
        HeaderValues(first.value, current ?: last.value, null, false)
    }
}
