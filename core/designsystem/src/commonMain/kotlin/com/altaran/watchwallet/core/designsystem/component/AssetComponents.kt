package com.altaran.watchwallet.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.altaran.watchwallet.core.designsystem.chart.Sparkline
import com.altaran.watchwallet.core.designsystem.generated.resources.Res
import com.altaran.watchwallet.core.designsystem.generated.resources.range_1m
import com.altaran.watchwallet.core.designsystem.generated.resources.range_1y
import com.altaran.watchwallet.core.designsystem.generated.resources.range_24h
import com.altaran.watchwallet.core.designsystem.generated.resources.range_3m
import com.altaran.watchwallet.core.designsystem.generated.resources.range_7d
import com.altaran.watchwallet.core.designsystem.generated.resources.range_all
import com.altaran.watchwallet.core.designsystem.generated.resources.range_ytd
import com.altaran.watchwallet.core.designsystem.theme.WatchWalletTheme
import com.altaran.watchwallet.core.designsystem.theme.tabularNumbers
import com.altaran.watchwallet.core.model.ChartRange
import org.jetbrains.compose.resources.stringResource

/**
 * Signed change with an arrow, colored by direction. The arrow and sign carry the meaning, so
 * it stays understandable without color.
 */
@Composable
fun ChangeText(
    formatted: String,
    direction: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    discreetAware: Boolean = false,
) {
    val colors = WatchWalletTheme.extendedColors
    val color = when {
        direction > 0 -> colors.positive
        direction < 0 -> colors.negative
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val arrow = when {
        direction > 0 -> "▲ "
        direction < 0 -> "▼ "
        else -> ""
    }
    if (discreetAware) {
        AmountText(arrow + formatted, modifier = modifier, style = style, color = color)
    } else {
        Text(arrow + formatted, modifier = modifier, style = style.tabularNumbers(), color = color, maxLines = 1)
    }
}

/**
 * Row of one asset (or holding): icon, symbol and amount on the start side; value and 24h change
 * on the end side; optional sparkline in between.
 */
@Composable
fun AssetRow(
    iconKey: String?,
    symbol: String,
    subtitle: String,
    value: String?,
    change: String?,
    changeDirection: Int,
    modifier: Modifier = Modifier,
    sparkline: List<Double> = emptyList(),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    iconUrl: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    onLongClick != null -> Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CryptoIcon(iconKey = iconKey, fallbackText = symbol, size = 40.dp, remoteUrl = iconUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AmountText(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (sparkline.size > 1) {
            val colors = WatchWalletTheme.extendedColors
            Sparkline(
                values = sparkline,
                color = if (sparkline.last() >= sparkline.first()) colors.positive else colors.negative,
                modifier = Modifier.size(width = 56.dp, height = 24.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            if (value != null) {
                AmountText(value, style = MaterialTheme.typography.titleSmall)
            } else {
                Text("—", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (change != null) ChangeText(change, changeDirection, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Horizontal chips to pick a chart range. */
@Composable
fun RangeSelector(
    selected: ChartRange,
    onSelect: (ChartRange) -> Unit,
    modifier: Modifier = Modifier,
    ranges: List<ChartRange> = ChartRange.entries,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ranges.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onSelect(range) },
                label = { Text(rangeLabel(range)) },
            )
        }
    }
}

@Composable
fun rangeLabel(range: ChartRange): String = stringResource(
    when (range) {
        ChartRange.H24 -> Res.string.range_24h
        ChartRange.D7 -> Res.string.range_7d
        ChartRange.M1 -> Res.string.range_1m
        ChartRange.M3 -> Res.string.range_3m
        ChartRange.Y1 -> Res.string.range_1y
        ChartRange.YTD -> Res.string.range_ytd
        ChartRange.ALL -> Res.string.range_all
    },
)

/** Small pill showing a network name with its icon. */
@Composable
fun ChainBadge(
    name: String,
    iconKey: String?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    androidx.compose.material3.Surface(color = color, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CryptoIcon(iconKey, name, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
