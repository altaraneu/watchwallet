package com.altaran.watchwallet.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Chart point; [flow] = cumulative net deposits up to this time (for performance without deposits). */
@Immutable
data class ChartPoint(val timeMillis: Long, val value: Double, val flow: Double = 0.0)

/** Tiny trend line for list rows. Color conveys direction, so callers also show a signed change. */
@Composable
fun Sparkline(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val range = (hi - lo).takeIf { it > 0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = (size.height * (1 - (v - lo) / range)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Immutable
data class DonutSlice(val value: Double, val color: Color)

/** Allocation donut. Slices below 1 % are merged by the caller into "Other". */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    thickness: Dp = 22.dp,
) {
    Canvas(modifier) {
        val total = slices.sumOf { it.value }.takeIf { it > 0 } ?: return@Canvas
        val stroke = thickness.toPx()
        val diameter = min(size.width, size.height) - stroke
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val gap = if (slices.size > 1) 2f else 0f
        var start = -90f
        slices.forEach { slice ->
            val sweep = (slice.value / total * 360).toFloat()
            drawArc(
                color = slice.color,
                startAngle = start + gap / 2,
                sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = stroke),
            )
            start += sweep
        }
    }
}

/** Distinct, accessible categorical colors for allocation slices (light and dark friendly). */
val AllocationPalette = listOf(
    Color(0xFF4E6AF0),
    Color(0xFF13A89E),
    Color(0xFFF2994A),
    Color(0xFFD14D8F),
    Color(0xFF8E6CEF),
    Color(0xFF56CCF2),
    Color(0xFFE5B800),
    Color(0xFF7F8C8D),
)
