package com.altaran.watchwallet.core.chart

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altaran.watchwallet.core.chart.generated.resources.Res
import com.altaran.watchwallet.core.designsystem.theme.WatchWalletTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** OHLC candle; [time] in UTC seconds (start of the interval). */
@Immutable
@Serializable
data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double)

/** Area/line point; [time] in UTC seconds. */
@Immutable
@Serializable
data class AreaPoint(val time: Long, val value: Double)

sealed interface ChartSeries {
    @Immutable
    data class Candles(val candles: List<Candle>) : ChartSeries

    @Immutable
    data class Area(val points: List<AreaPoint>) : ChartSeries
}

/** Value under the crosshair, or `null` when the finger is lifted. */
data class CrosshairValue(val time: Long, val value: Double, val open: Double?, val high: Double?, val low: Double?)

/**
 * What the chart shows after pan or zoom.
 *
 * @property from time (UTC seconds) of the first visible bar.
 * @property to time (UTC seconds) of the last visible bar.
 * @property bars number of visible bars.
 * @property barsBefore number of bars to the left of the view.
 * @property userMoved the user changed the view since the window was last reset.
 */
@Immutable
@Serializable
data class ChartView(val from: Long, val to: Long, val bars: Int = 0, val barsBefore: Int = 0, val userMoved: Boolean = false)

/** How prices are labelled on the axis and crosshair (formatted in JS with `Intl.NumberFormat`). */
@Immutable
data class ChartNumberFormat(
    val localeTag: String,
    /** ISO currency for `style: currency`; `null` = plain number with [suffix]. */
    val currency: String?,
    val suffix: String? = null,
    val minDecimals: Int = 2,
    val maxDecimals: Int = 2,
)

/**
 * Interactive financial chart backed by TradingView Lightweight Charts (Apache 2.0), rendered
 * offline in a WebView: pan, pinch zoom, crosshair, candlesticks or area.
 *
 * - Changing [series] replaces the data; [live] updates (or appends) the last bar without a
 *   full redraw — use it for streaming prices.
 * - [hideValues] (discreet mode) hides the price axis and every numeric label.
 * - Axis labels show the time of day: the bars of one series can be anything from minutes to days,
 *   and switching the format while the user zooms would have to recreate the chart.
 */
@Composable
fun TradingChart(
    series: ChartSeries,
    format: ChartNumberFormat,
    modifier: Modifier = Modifier,
    height: Dp = 280.dp,
    live: Candle? = null,
    logScale: Boolean = false,
    hideValues: Boolean = false,
    markerTimes: List<Long> = emptyList(),
    fitOnDataChange: Boolean = true,
    /** Initially visible number of bars (most recent); 0 = fit all data. */
    visibleBars: Int = 0,
    /** Initially visible from this time (UTC seconds) to the latest bar; wins over [visibleBars]. */
    visibleFrom: Long? = null,
    /**
     * Identifies what the data shows (range, interval, mode…). Refreshes with the same key keep
     * the user's pan, zoom and tapped bar; a new key resets the view to [visibleBars].
     */
    windowKey: String = "",
    lineColor: Color? = null,
    onCrosshair: (CrosshairValue?) -> Unit = {},
    /** Visible window after pan or zoom; reported on every frame of a gesture. */
    onVisibleRange: (ChartView) -> Unit = {},
    /** Spoken by screen readers instead of the (inaccessible) chart canvas. */
    contentDescription: String? = null,
) {
    val html by produceState<String?>(null) { value = loadChartHtml() }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = chartColors()
    val lineColors = lineColors(lineColor)
    val kind = if (series is ChartSeries.Candles) "candles" else "area"
    val controller = remember { ChartController() }
    val currentOnCrosshair by rememberUpdatedState(onCrosshair)
    val currentOnVisibleRange by rememberUpdatedState(onVisibleRange)

    val initOptions = remember(kind, colors, format, logScale, hideValues, dark) {
        JSON.encodeToString(
            InitOptions(
                kind = kind,
                locale = format.localeTag,
                currencyStyle = format.currency != null,
                currency = format.currency ?: "USD",
                suffix = format.suffix,
                minDecimals = format.minDecimals,
                maxDecimals = format.maxDecimals,
                logScale = logScale,
                hideValues = hideValues,
                colors = colors,
            ),
        )
    }

    // init() recreates the chart; the page keeps markers and line colours and re-applies them,
    // and the last data is replayed here (the data effect below does not rerun for new options).
    LaunchedEffect(initOptions) {
        controller.run("init($initOptions)")
        controller.reapplyData()
    }
    LaunchedEffect(series, fitOnDataChange, visibleBars, visibleFrom, windowKey) {
        val js = withContext(Dispatchers.Default) {
            val json = when (series) {
                is ChartSeries.Candles -> JSON.encodeToString(series.candles.distinctBy { it.time }.sortedBy { it.time })
                is ChartSeries.Area -> JSON.encodeToString(series.points.distinctBy { it.time }.sortedBy { it.time })
            }
            "setData($json, $fitOnDataChange, $visibleBars, ${JSON.encodeToString(windowKey)}, ${visibleFrom ?: 0})"
        }
        controller.setData(js)
    }
    LaunchedEffect(lineColors) {
        controller.run("setLineColors(${JSON.encodeToString(lineColors)})")
    }
    LaunchedEffect(live) {
        live?.let { controller.run("update(${JSON.encodeToString(it)})") }
    }
    LaunchedEffect(markerTimes) {
        val json = withContext(Dispatchers.Default) { JSON.encodeToString(markerTimes.distinct().sorted()) }
        controller.run("setMarkers($json)")
    }

    html?.let {
        ChartWebView(
            html = it,
            controller = controller,
            onCrosshair = { payload -> currentOnCrosshair(payload) },
            onVisibleRange = { currentOnVisibleRange(it) },
            modifier = modifier.fillMaxWidth().height(height).let { base ->
                if (contentDescription == null) base else base.clearAndSetSemantics { this.contentDescription = contentDescription }
            },
        )
    }
}

/** Serial queue of JavaScript calls; buffers calls until the page has loaded. */
class ChartController {
    private var executor: ((String) -> Unit)? = null
    private val pending = mutableListOf<String>()
    private var lastData: String? = null

    fun run(js: String) {
        val exec = executor
        if (exec == null) pending += js else exec(js)
    }

    internal fun setData(js: String) {
        lastData = js
        run(js)
    }

    internal fun reapplyData() {
        lastData?.let { run(it) }
    }

    /** Called by the platform view once the page is ready. */
    fun attach(exec: (String) -> Unit) {
        executor = exec
        pending.toList().forEach(exec)
        pending.clear()
    }

    fun detach() {
        executor = null
    }
}

@Composable
internal expect fun ChartWebView(
    html: String,
    controller: ChartController,
    onCrosshair: (CrosshairValue?) -> Unit,
    onVisibleRange: (ChartView) -> Unit,
    modifier: Modifier,
)

internal val JSON = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

@Serializable
internal data class CrosshairPayload(val time: Long, val value: Double, val open: Double? = null, val high: Double? = null, val low: Double? = null)

internal fun parseVisibleRange(payload: String?): ChartView? =
    payload?.let { runCatching { JSON.decodeFromString<ChartView>(it) }.getOrNull() }

internal fun parseCrosshair(payload: String?): CrosshairValue? {
    if (payload.isNullOrBlank() || payload == "null") return null
    return runCatching { JSON.decodeFromString<CrosshairPayload>(payload) }.getOrNull()
        ?.let { CrosshairValue(it.time, it.value, it.open, it.high, it.low) }
}

@Serializable
private data class InitOptions(
    val kind: String,
    val locale: String,
    val currencyStyle: Boolean,
    val currency: String,
    val suffix: String?,
    val minDecimals: Int,
    val maxDecimals: Int,
    val logScale: Boolean,
    val hideValues: Boolean,
    val colors: ChartColors,
)

@Serializable
internal data class ChartColors(
    val background: String,
    val text: String,
    val grid: String,
    val accent: String,
    val up: String,
    val down: String,
    val line: String,
    val areaTop: String,
    val areaBottom: String,
    val marker: String,
)

/** Line and area fill of the area series; applied without recreating the chart. */
@Serializable
internal data class LineColors(val line: String, val areaTop: String, val areaBottom: String)

@Composable
private fun lineColors(lineColor: Color?): LineColors {
    val line = lineColor ?: MaterialTheme.colorScheme.primary
    return remember(line) {
        LineColors(line = line.css(), areaTop = line.copy(alpha = 0.30f).css(), areaBottom = line.copy(alpha = 0.0f).css())
    }
}

@Composable
private fun chartColors(): ChartColors {
    val scheme = MaterialTheme.colorScheme
    val extended = WatchWalletTheme.extendedColors
    val line = scheme.primary
    return remember(scheme, extended, line) {
        ChartColors(
            background = scheme.surface.css(),
            text = scheme.onSurfaceVariant.css(),
            grid = scheme.outlineVariant.copy(alpha = 0.35f).css(),
            accent = scheme.secondary.css(),
            up = extended.positive.css(),
            down = extended.negative.css(),
            line = line.css(),
            areaTop = line.copy(alpha = 0.30f).css(),
            areaBottom = line.copy(alpha = 0.0f).css(),
            marker = scheme.tertiary.css(),
        )
    }
}

private fun Color.css(): String {
    val argb = toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val a = ((argb ushr 24) and 0xFF) / 255.0
    return "rgba($r,$g,$b,${(a * 1000).toInt() / 1000.0})"
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private var cachedHtml: String? = null

@OptIn(ExperimentalResourceApi::class)
private suspend fun loadChartHtml(): String {
    cachedHtml?.let { return it }
    val template = Res.readBytes("files/chart/chart.html").decodeToString()
    val library = Res.readBytes("files/chart/lightweight-charts.js").decodeToString()
    return template.replace("/*LIGHTWEIGHT_CHARTS*/", library).also { cachedHtml = it }
}
