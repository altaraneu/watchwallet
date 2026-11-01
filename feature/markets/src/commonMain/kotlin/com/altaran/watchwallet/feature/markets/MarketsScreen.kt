package com.altaran.watchwallet.feature.markets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altaran.watchwallet.core.designsystem.component.AssetRow
import com.altaran.watchwallet.core.designsystem.component.CryptoIcons
import com.altaran.watchwallet.core.designsystem.component.EmptyState
import com.altaran.watchwallet.core.designsystem.component.SectionHeader
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.chart.ChartNumberFormat
import com.altaran.watchwallet.core.chart.ChartSeries
import com.altaran.watchwallet.core.chart.CrosshairValue
import com.altaran.watchwallet.core.chart.TradingChart
import com.altaran.watchwallet.core.model.CandleInterval
import com.altaran.watchwallet.core.ui.ValueChartSection
import com.altaran.watchwallet.core.ui.ValueHeader
import com.altaran.watchwallet.core.ui.chartFormatFor
import com.altaran.watchwallet.core.ui.format.DateFormatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.intl.Locale
import kotlin.time.Instant
import com.altaran.watchwallet.core.ui.format.MoneyFormatter
import com.altaran.watchwallet.feature.markets.generated.resources.Res
import com.altaran.watchwallet.feature.markets.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun MarketsRoute(onOpenAsset: (String) -> Unit, viewModel: MarketsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MarketsScreen(state, onOpenAsset, viewModel::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarketsScreen(state: MarketsUiState, onOpenAsset: (String) -> Unit, onRefresh: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(stringResource(Res.string.markets_title)) }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!state.loading && state.rows.isEmpty()) {
                EmptyState(
                    icon = WwIcon.Markets,
                    title = stringResource(if (state.failed) Res.string.markets_failed_title else Res.string.markets_empty_title),
                    message = stringResource(if (state.failed) Res.string.markets_failed_message else Res.string.markets_empty_message),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    val (held, others) = state.rows.partition { it.priceId in state.heldIds }
                    if (held.isNotEmpty()) item { SectionHeader(stringResource(Res.string.markets_held)) }
                    items(held, key = { "h" + it.priceId }) { row -> MarketRow(row, state, onOpenAsset) }
                    if (others.isNotEmpty()) item { SectionHeader(stringResource(Res.string.markets_watchlist)) }
                    items(others, key = { "w" + it.priceId }) { row -> MarketRow(row, state, onOpenAsset) }
                }
            }
        }
    }
}

@Composable
private fun MarketRow(row: com.altaran.watchwallet.core.pricing.MarketInfo, state: MarketsUiState, onOpen: (String) -> Unit) {
    AssetRow(
        iconKey = CryptoIcons.forSymbol(row.symbol),
        symbol = row.symbol,
        subtitle = row.name,
        value = MoneyFormatter.formatPrice(row.price, state.currency),
        change = row.change24hPercent?.let { MoneyFormatter.formatPercentChange(it / 100) },
        changeDirection = row.change24hPercent?.compareTo(0.0) ?: 0,
        sparkline = row.sparkline7d,
        onClick = { onOpen(row.priceId) },
    )
}

@Composable
internal fun AssetDetailRoute(
    key: String,
    onNavigateUp: () -> Unit,
    viewModel: AssetDetailViewModel = koinViewModel(key = "asset-$key") { parametersOf(key) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AssetDetailScreen(state, onNavigateUp, viewModel::setMode, viewModel::setRange, viewModel::setInterval, viewModel::toggleLogScale)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssetDetailScreen(
    state: AssetDetailUiState,
    onNavigateUp: () -> Unit,
    onModeChange: (AssetChartMode) -> Unit,
    onRangeChange: (com.altaran.watchwallet.core.model.ChartRange) -> Unit,
    onIntervalChange: (CandleInterval) -> Unit,
    onToggleLog: () -> Unit,
) {
    val symbol = state.holding?.symbol ?: state.market?.symbol.orEmpty()
    val name = state.holding?.name ?: state.market?.name.orEmpty()
    val currency = state.currency
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (name.isNotEmpty()) "$name ($symbol)" else symbol) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) { Icon(WwIcon.ArrowBack.painter(), stringResource(Res.string.markets_back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (state.holding != null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    AssetChartMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = { onModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, AssetChartMode.entries.size),
                        ) {
                            Text(
                                stringResource(
                                    when (mode) {
                                        AssetChartMode.PRICE -> Res.string.asset_mode_price
                                        AssetChartMode.VALUE -> Res.string.asset_mode_value
                                        AssetChartMode.BALANCE -> Res.string.asset_mode_balance
                                    },
                                ),
                            )
                        }
                    }
                }
            }
            val isBalance = state.mode == AssetChartMode.BALANCE
            val candles = state.candles
            if (state.mode == AssetChartMode.PRICE && candles is CandleChartState.Ready) {
                CandleSection(state, candles, symbol, onIntervalChange)
            } else {
                val current = when (state.mode) {
                    AssetChartMode.PRICE -> state.holding?.price ?: state.market?.price
                    AssetChartMode.VALUE -> state.holding?.value
                    AssetChartMode.BALANCE -> state.holding?.amount
                }
                ValueChartSection(
                    label = modeLabel(state.mode),
                    currentValue = current,
                    points = if (state.mode == AssetChartMode.PRICE && candles is CandleChartState.Loading) null else state.chart,
                    range = state.range,
                    onRangeChange = onRangeChange,
                    logScale = state.logScale,
                    numberFormat = if (isBalance) {
                        ChartNumberFormat(Locale.current.toLanguageTag(), null, suffix = symbol, minDecimals = 0, maxDecimals = 8)
                    } else {
                        chartFormatFor(currency, maxDecimals = priceDecimals(current))
                    },
                    format = { if (isBalance) MoneyFormatter.formatCrypto(it, symbol) else MoneyFormatter.formatPrice(it, currency) },
                    formatChange = { value ->
                        if (isBalance) (if (value >= 0) "+" else "−") + MoneyFormatter.formatCrypto(kotlin.math.abs(value), symbol) else MoneyFormatter.formatChange(value, currency)
                    },
                )
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.logScale, onClick = onToggleLog, label = { Text(stringResource(Res.string.asset_log_scale)) })
            }
            state.holding?.let { holding ->
                SectionHeader(stringResource(Res.string.asset_your_holding))
                Stat(stringResource(Res.string.asset_amount), MoneyFormatter.formatCrypto(holding.amount, holding.symbol))
                holding.value?.let { Stat(stringResource(Res.string.asset_mode_value), MoneyFormatter.format(it, currency)) }
            }
            state.market?.let { market ->
                SectionHeader(stringResource(Res.string.asset_market))
                Stat(stringResource(Res.string.asset_mode_price), MoneyFormatter.formatPrice(market.price, currency), discreet = false)
                market.change24hPercent?.let { Stat(stringResource(Res.string.asset_change_24h), MoneyFormatter.formatPercentChange(it / 100), discreet = false) }
                market.change7dPercent?.let { Stat(stringResource(Res.string.asset_change_7d), MoneyFormatter.formatPercentChange(it / 100), discreet = false) }
                market.marketCap?.let { Stat(stringResource(Res.string.asset_market_cap), MoneyFormatter.format(it, currency), discreet = false) }
            }
        }
    }
}

@Composable
private fun modeLabel(mode: AssetChartMode): String = stringResource(
    when (mode) {
        AssetChartMode.PRICE -> Res.string.asset_mode_price
        AssetChartMode.VALUE -> Res.string.asset_mode_value
        AssetChartMode.BALANCE -> Res.string.asset_mode_balance
    },
)

private fun priceDecimals(price: Double?): Int = when {
    price == null || price >= 100 -> 2
    price >= 1 -> 4
    price >= 0.01 -> 6
    else -> 8
}

/** Exchange-style candlestick chart with interval chips; updates live from the exchange feed. */
@Composable
private fun CandleSection(state: AssetDetailUiState, candles: CandleChartState.Ready, symbol: String, onIntervalChange: (CandleInterval) -> Unit) {
    var crosshair by remember(candles.candles) { mutableStateOf<CrosshairValue?>(null) }
    val last = candles.live ?: candles.candles.lastOrNull()
    val first = candles.candles.firstOrNull()
    val shown = crosshair?.value ?: last?.close
    val currency = state.currency
    ValueHeader(
        label = crosshair?.let { c ->
            DateFormatter.dateTime(Instant.fromEpochSeconds(c.time)) +
                if (c.open != null && c.high != null && c.low != null) {
                    "  " + stringResource(
                        Res.string.asset_ohlc,
                        MoneyFormatter.formatPrice(c.open!!, currency),
                        MoneyFormatter.formatPrice(c.high!!, currency),
                        MoneyFormatter.formatPrice(c.low!!, currency),
                    )
                } else {
                    ""
                }
        } ?: stringResource(Res.string.asset_live_price, symbol),
        value = shown?.let { MoneyFormatter.formatPrice(it, currency) },
        change = if (first != null && shown != null) shown - first.open else null,
        changePercent = if (first != null && shown != null && first.open > 0) (shown - first.open) / first.open else null,
        formatChange = { MoneyFormatter.formatChange(it, currency) },
        rangeText = null,
    )
    TradingChart(
        series = ChartSeries.Candles(candles.candles),
        live = candles.live,
        format = chartFormatFor(currency, maxDecimals = priceDecimals(last?.close)),
        height = 320.dp,
        intraday = state.interval.isIntraday,
        logScale = state.logScale,
        onCrosshair = { crosshair = it },
        modifier = Modifier.padding(vertical = 8.dp),
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CandleInterval.entries.forEach { interval ->
            FilterChip(selected = state.interval == interval, onClick = { onIntervalChange(interval) }, label = { Text(intervalLabel(interval)) })
        }
    }
    Text(
        stringResource(Res.string.asset_source_kraken),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun intervalLabel(interval: CandleInterval): String = stringResource(
    when (interval) {
        CandleInterval.M1 -> Res.string.interval_1m
        CandleInterval.M5 -> Res.string.interval_5m
        CandleInterval.M15 -> Res.string.interval_15m
        CandleInterval.H1 -> Res.string.interval_1h
        CandleInterval.H4 -> Res.string.interval_4h
        CandleInterval.D1 -> Res.string.interval_1d
        CandleInterval.W1 -> Res.string.interval_1w
    },
)

@Composable
private fun Stat(label: String, value: String, discreet: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (discreet) {
            com.altaran.watchwallet.core.designsystem.component.AmountText(value, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
