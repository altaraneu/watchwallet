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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altaran.watchwallet.core.chart.generated.resources.Res
import com.altaran.watchwallet.core.designsystem.theme.WatchWalletTheme
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
 */
@Composable
fun TradingChart(
    series: ChartSeries,
    format: ChartNumberFormat,
    modifier: Modifier = Modifier,
    height: Dp = 280.dp,
    live: Candle? = null,
    intraday: Boolean = false,
    logScale: Boolean = false,
    hideValues: Boolean = false,
    markerTimes: List<Long> = emptyList(),
    fitOnDataChange: Boolean = true,
    lineColor: Color? = null,
    onCrosshair: (CrosshairValue?) -> Unit = {},
) {
    val html by produceState<String?>(null) { value = loadChartHtml() }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = chartColors(lineColor)
    val kind = if (series is ChartSeries.Candles) "candles" else "area"
    val controller = remember { ChartController() }
    val currentOnCrosshair by rememberUpdatedState(onCrosshair)

    val initOptions = remember(kind, colors, format, intraday, logScale, hideValues, dark) {
        JSON.encodeToString(
            InitOptions(
                kind = kind,
                locale = format.localeTag,
                currencyStyle = format.currency != null,
                currency = format.currency ?: "USD",
                suffix = format.suffix,
                minDecimals = format.minDecimals,
                maxDecimals = format.maxDecimals,
                intraday = intraday,
                logScale = logScale,
                hideValues = hideValues,
                colors = colors,
            ),
        )
    }

    LaunchedEffect(initOptions) {
        controller.run("init($initOptions)")
        controller.reapplyData()
    }
    LaunchedEffect(series, initOptions) {
        val json = when (series) {
            is ChartSeries.Candles -> JSON.encodeToString(series.candles.distinctBy { it.time }.sortedBy { it.time })
            is ChartSeries.Area -> JSON.encodeToString(series.points.distinctBy { it.time }.sortedBy { it.time })
        }
        controller.setData("setData($json, $fitOnDataChange)")
    }
    LaunchedEffect(live) {
        live?.let { controller.run("update(${JSON.encodeToString(it)})") }
    }
    LaunchedEffect(markerTimes, series) {
        if (markerTimes.isNotEmpty()) controller.run("setMarkers(${JSON.encodeToString(markerTimes.distinct().sorted())})")
    }

    html?.let {
        ChartWebView(
            html = it,
            controller = controller,
            onCrosshair = { payload -> currentOnCrosshair(payload) },
            modifier = modifier.fillMaxWidth().height(height),
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
    modifier: Modifier,
)

internal val JSON = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

@Serializable
internal data class CrosshairPayload(val time: Long, val value: Double, val open: Double? = null, val high: Double? = null, val low: Double? = null)

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
    val intraday: Boolean,
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

@Composable
private fun chartColors(lineColor: Color?): ChartColors {
    val scheme = MaterialTheme.colorScheme
    val extended = WatchWalletTheme.extendedColors
    val line = lineColor ?: scheme.primary
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
