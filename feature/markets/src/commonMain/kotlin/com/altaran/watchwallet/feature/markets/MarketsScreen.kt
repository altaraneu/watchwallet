package com.altaran.watchwallet.feature.markets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altaran.watchwallet.core.chart.ChartNumberFormat
import com.altaran.watchwallet.core.chart.ChartSeries
import com.altaran.watchwallet.core.chart.ChartView
import com.altaran.watchwallet.core.chart.CrosshairValue
import com.altaran.watchwallet.core.chart.TradingChart
import com.altaran.watchwallet.core.designsystem.component.AssetRow
import com.altaran.watchwallet.core.designsystem.component.CryptoIcon
import com.altaran.watchwallet.core.designsystem.component.CryptoIcons
import com.altaran.watchwallet.core.designsystem.component.EmptyState
import com.altaran.watchwallet.core.designsystem.component.SectionHeader
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.model.CandleInterval
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.ui.TransactionDetails
import com.altaran.watchwallet.core.ui.TransactionRow
import com.altaran.watchwallet.core.ui.TxView
import com.altaran.watchwallet.core.ui.ValueChartSection
import com.altaran.watchwallet.core.ui.ValueHeader
import com.altaran.watchwallet.core.ui.chartFormatFor
import com.altaran.watchwallet.core.ui.format.DateFormatter
import com.altaran.watchwallet.core.ui.format.MoneyFormatter
import com.altaran.watchwallet.feature.markets.generated.resources.*
import com.altaran.watchwallet.feature.markets.generated.resources.Res
import com.altaran.watchwallet.feature.markets.generated.resources.asset_activity
import com.altaran.watchwallet.feature.markets.generated.resources.asset_cost_basis
import com.altaran.watchwallet.feature.markets.generated.resources.asset_cost_incomplete
import com.altaran.watchwallet.feature.markets.generated.resources.asset_current_price
import com.altaran.watchwallet.feature.markets.generated.resources.asset_in_wallets
import com.altaran.watchwallet.feature.markets.generated.resources.asset_unrealized
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Instant

@Composable
internal fun MarketsRoute(onOpenAsset: (String) -> Unit, viewModel: MarketsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    MarketsScreen(state, onOpenAsset, viewModel::refresh, onAdd = { searching = true })
    if (searching) {
        WatchlistSearchDialog(
            results = results,
            onQuery = viewModel::search,
            onPick = { coin ->
                viewModel.addToWatchlist(coin)
                searching = false
            },
            onDismiss = { searching = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarketsScreen(state: MarketsUiState, onOpenAsset: (String) -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit = {}) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.markets_title)) },
                actions = {
                    IconButton(onClick = onAdd) { Icon(WwIcon.Add.painter(), stringResource(Res.string.watchlist_add)) }
                },
                scrollBehavior = scrollBehavior,
            )
        },
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
                    if (state.poweredByCoinGecko) item { PoweredByCoinGecko(Modifier.padding(16.dp)) }
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
        iconUrl = com.altaran.watchwallet.core.data.AssetIcons.url(row.priceId),
    )
}

@Composable
internal fun AssetDetailRoute(
    key: String,
    onNavigateUp: () -> Unit,
    onOpenWallet: (Long) -> Unit = {},
    viewModel: AssetDetailViewModel = koinViewModel(key = "asset-$key") { parametersOf(key) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AssetDetailScreen(
        state,
        onNavigateUp,
        viewModel::setMode,
        viewModel::setRange,
        viewModel::setInterval,
        viewModel::toggleLogScale,
        viewModel::toggleWatch,
        onOpenWallet = onOpenWallet,
        onSetCost = viewModel::setCost,
        onVisibleWindow = viewModel::onVisibleWindow,
        onCandleView = viewModel::onCandleView,
    )
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
    onToggleWatch: (Boolean) -> Unit = {},
    onOpenWallet: (Long) -> Unit = {},
    onSetCost: (TxView, Double?) -> Unit = { _, _ -> },
    onVisibleWindow: (Long, Long) -> Unit = { _, _ -> },
    onCandleView: (ChartView) -> Unit = {},
) {
    var selectedTx by remember { mutableStateOf<TxView?>(null) }
    val uriHandler = LocalUriHandler.current
    TransactionDetails(selectedTx, onSelect = { selectedTx = it }, onSetCost = onSetCost, onOpenExplorer = { uriHandler.openUri(it) })
    val symbol = state.holding?.symbol ?: state.market?.symbol.orEmpty()
    val name = state.holding?.name ?: state.market?.name.orEmpty()
    val currency = state.currency
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CryptoIcon(
                            iconKey = CryptoIcons.forSymbol(symbol),
                            fallbackText = symbol,
                            size = 32.dp,
                            remoteUrl = com.altaran.watchwallet.core.data.AssetIcons.url(state.holding?.priceId ?: state.market?.priceId),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (name.isNotEmpty()) stringResource(Res.string.asset_title, name, symbol) else symbol,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) { Icon(WwIcon.ArrowBack.painter(), stringResource(Res.string.markets_back)) }
                },
                actions = {
                    state.watched?.let { watched ->
                        FilterChip(
                            selected = watched,
                            onClick = { onToggleWatch(watched) },
                            label = { Text(stringResource(if (watched) Res.string.watchlist_watching else Res.string.watchlist_watch)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
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
                CandleSection(state, candles, symbol, onIntervalChange, onCandleView)
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
                        when (state.mode) {
                            AssetChartMode.BALANCE -> formatCryptoChange(value, symbol)
                            AssetChartMode.PRICE -> MoneyFormatter.formatPriceChange(value, currency)
                            AssetChartMode.VALUE -> MoneyFormatter.formatChange(value, currency)
                        }
                    },
                    windowKey = state.mode.name,
                    // The line chart of prices is a fallback from stored prices without minutes.
                    ranges = if (state.mode == AssetChartMode.PRICE) ChartRange.entries - ChartRange.H1 else ChartRange.entries,
                    onVisibleWindow = if (state.mode == AssetChartMode.PRICE) { _, _ -> } else onVisibleWindow,
                )
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.logScale, onClick = onToggleLog, label = { Text(stringResource(Res.string.asset_log_scale)) })
            }
            state.holding?.let { holding ->
                SectionHeader(stringResource(Res.string.asset_your_holding))
                Stat(stringResource(Res.string.asset_amount), MoneyFormatter.formatCrypto(holding.amount, holding.symbol))
                holding.value?.let { Stat(stringResource(Res.string.asset_mode_value), MoneyFormatter.format(it, currency)) }
                state.costUsd?.let { costUsd ->
                    val usd = com.altaran.watchwallet.core.model.QuoteCurrency.USD
                    Stat(stringResource(Res.string.asset_cost_basis), MoneyFormatter.format(costUsd, usd))
                    state.valueUsd?.let { value ->
                        val pl = value - costUsd
                        val pct = if (costUsd > 0) MoneyFormatter.formatPercentChange(pl / costUsd) else null
                        val plText = MoneyFormatter.formatChange(pl, usd)
                        Stat(
                            stringResource(Res.string.asset_unrealized),
                            pct?.let { stringResource(Res.string.asset_change_with_percent, plText, it) } ?: plText,
                        )
                    }
                    if (!state.costComplete) {
                        Text(
                            stringResource(Res.string.asset_cost_incomplete),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            if (state.wallets.isNotEmpty()) {
                SectionHeader(stringResource(Res.string.asset_in_wallets))
                state.wallets.forEach { share -> WalletShareRow(share, symbol, currency) { onOpenWallet(share.walletId) } }
            }
            if (state.activity.isNotEmpty()) {
                SectionHeader(stringResource(Res.string.asset_activity))
                state.activity.forEach { tx ->
                    TransactionRow(tx) { selectedTx = tx }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
            state.market?.let { market ->
                SectionHeader(stringResource(Res.string.asset_market))
                // The same price as the header above: the live one once the exchange feed runs.
                val livePrice = (state.candles as? CandleChartState.Ready)?.let { it.live ?: it.candles.lastOrNull() }?.close
                Stat(stringResource(Res.string.asset_mode_price), MoneyFormatter.formatPrice(livePrice ?: market.price, currency), discreet = false)
                market.change24hPercent?.let {
                    Stat(stringResource(Res.string.asset_change_24h), MoneyFormatter.formatPercentChange(it / 100), discreet = false)
                }
                market.change7dPercent?.let { Stat(stringResource(Res.string.asset_change_7d), MoneyFormatter.formatPercentChange(it / 100), discreet = false) }
                market.marketCap?.let { Stat(stringResource(Res.string.asset_market_cap), MoneyFormatter.format(it, currency), discreet = false) }
            }
            if (state.poweredByCoinGecko) PoweredByCoinGecko(Modifier.padding(16.dp))
        }
    }
}

/** Required by CoinGecko's API terms where its data is displayed. The name is not translated. */
@Composable
private fun PoweredByCoinGecko(modifier: Modifier = Modifier) {
    Text(
        stringResource(Res.string.powered_by_coingecko),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun WatchlistSearchDialog(
    results: List<com.altaran.watchwallet.core.pricing.CoinSearchResult>,
    onQuery: (String) -> Unit,
    onPick: (com.altaran.watchwallet.core.pricing.CoinSearchResult) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(query) {
        if (query.length >= 2) {
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            onQuery(query)
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.watchlist_add)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(Res.string.watchlist_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Search results always come from CoinGecko.
                if (results.isNotEmpty()) PoweredByCoinGecko(Modifier.padding(top = 8.dp))
                Column(Modifier.padding(top = 8.dp).verticalScroll(rememberScrollState())) {
                    results.forEach { coin ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(coin) }.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(coin.symbol, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(64.dp))
                            Text(coin.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            coin.marketCapRank?.let {
                                Text(
                                    stringResource(Res.string.watchlist_rank, it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(Res.string.watchlist_cancel)) } },
    )
}

private const val SEARCH_DEBOUNCE_MS = 400L

@Composable
private fun modeLabel(mode: AssetChartMode): String = stringResource(
    when (mode) {
        AssetChartMode.PRICE -> Res.string.asset_mode_price
        AssetChartMode.VALUE -> Res.string.asset_mode_value
        AssetChartMode.BALANCE -> Res.string.asset_mode_balance
    },
)

// U+2212 MINUS SIGN, like MoneyFormatter's signed changes.
private val MINUS_SIGN = Char(0x2212)

/** Signed crypto amount (`+0,5 BTC`, `−0,1 BTC`), following MoneyFormatter's sign rules. */
private fun formatCryptoChange(value: Double, symbol: String): String {
    val sign = when {
        value > 0 -> "+"
        value < 0 -> MINUS_SIGN.toString()
        else -> ""
    }
    return MoneyFormatter.isolated(sign + MoneyFormatter.formatCrypto(kotlin.math.abs(value), symbol))
}

private fun priceDecimals(price: Double?): Int = when {
    price == null || price >= 100 -> 2
    price >= 1 -> 4
    price >= 0.01 -> 6
    else -> 8
}

/**
 * Exchange-style candlestick chart with interval chips; updates live from the exchange feed.
 * The header shows the change across the **visible** window (it follows pan and zoom); tapping a
 * candle shows its date with high, low and volume-weighted average price.
 */
@Composable
private fun CandleSection(
    state: AssetDetailUiState,
    candles: CandleChartState.Ready,
    symbol: String,
    onIntervalChange: (CandleInterval) -> Unit,
    onCandleView: (ChartView) -> Unit,
) {
    val currency = state.currency
    // Pan, zoom and the tapped candle survive data refreshes and interval changes caused by zooming
    // (the chart page keeps the time window); a chip or another currency resets them.
    val windowKey = "${state.candleViewKey}|${currency.code}"
    var crosshair by remember(windowKey) { mutableStateOf<CrosshairValue?>(null) }
    var window by remember(windowKey) { mutableStateOf<Pair<Long, Long>?>(null) }
    var view by remember(windowKey) { mutableStateOf<ChartView?>(null) }
    val currentOnCandleView by rememberUpdatedState(onCandleView)
    // Once the gesture has settled: the candle width does not change under a moving finger.
    LaunchedEffect(view) {
        val settled = view ?: return@LaunchedEffect
        delay(CANDLE_SETTLE_MS)
        currentOnCandleView(settled)
    }
    val all = candles.candles
    val live = candles.live
    val last = live ?: all.lastOrNull()
    val visibleBars = state.interval.visibleBars
    val firstVisible = remember(all, window, visibleBars) {
        window?.let { (from, _) -> all.firstOrNull { it.time >= from } } ?: all.getOrNull(all.size - visibleBars) ?: all.firstOrNull()
    }
    val lastVisible = remember(all, live, window) {
        window?.let { (_, to) -> live?.takeIf { it.time <= to } ?: all.lastOrNull { it.time <= to } } ?: last
    }
    val shown = crosshair?.value ?: lastVisible?.close
    val crosshairTime = crosshair?.time
    val selected = remember(all, live, crosshairTime) {
        crosshairTime?.let { t -> live?.takeIf { it.time == t } ?: all.firstOrNull { it.time == t } }
    }
    val series = remember(all) { ChartSeries.Candles(all) }

    ValueHeader(
        label = crosshair?.let { DateFormatter.dateTime(Instant.fromEpochSeconds(it.time)) } ?: stringResource(Res.string.asset_live_price, symbol),
        value = shown?.let { MoneyFormatter.formatPrice(it, currency) },
        change = if (firstVisible != null && shown != null) shown - firstVisible.open else null,
        changePercent = if (firstVisible != null && shown != null && firstVisible.open > 0) (shown - firstVisible.open) / firstVisible.open else null,
        formatChange = { MoneyFormatter.formatPriceChange(it, currency) },
        rangeText = if (crosshair == null) stringResource(Res.string.asset_visible_range) else null,
        // A selected candle is in the past: say what the price is now.
        secondaryLine = last?.takeIf {
            crosshair != null
        }?.let { stringResource(Res.string.asset_current_price, MoneyFormatter.formatPrice(it.close, currency)) },
        reserveSecondaryLine = true,
    )
    // Always there, empty without a selected candle, so that the chart does not move on a tap.
    val vwap = selected?.let { candles.vwap[it.time] }?.takeIf { it > 0 }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CandleStat(stringResource(Res.string.asset_high), selected?.let { MoneyFormatter.formatPrice(it.high, currency) } ?: NO_VALUE)
        CandleStat(stringResource(Res.string.asset_low), selected?.let { MoneyFormatter.formatPrice(it.low, currency) } ?: NO_VALUE)
        CandleStat(stringResource(Res.string.asset_average), vwap?.let { MoneyFormatter.formatPrice(it, currency) } ?: NO_VALUE)
    }
    TradingChart(
        series = series,
        live = live,
        format = chartFormatFor(currency, maxDecimals = priceDecimals(last?.close)),
        height = 320.dp,
        logScale = state.logScale,
        visibleBars = visibleBars,
        windowKey = windowKey,
        onCrosshair = { crosshair = it },
        onVisibleRange = {
            window = it.from to it.to
            view = it
        },
        modifier = Modifier.padding(vertical = 8.dp),
        contentDescription = last?.let { stringResource(Res.string.asset_chart_a11y, symbol, MoneyFormatter.formatPrice(it.close, currency)) },
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CandleInterval.entries.forEach { interval ->
            FilterChip(selected = state.interval == interval, onClick = { onIntervalChange(interval) }, label = { Text(intervalLabel(interval)) })
        }
    }
}

/** How long the candle view must rest before the width follows it. */
private const val CANDLE_SETTLE_MS = 300L

/** Placeholder of the candle details while no candle is selected. */
private const val NO_VALUE = "—"

@Composable
private fun CandleStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
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

/** One wallet holding this asset; opens the wallet. */
@Composable
private fun WalletShareRow(share: WalletShare, symbol: String, currency: com.altaran.watchwallet.core.model.QuoteCurrency, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(share.name, style = MaterialTheme.typography.titleSmall)
            com.altaran.watchwallet.core.designsystem.component.AmountText(
                MoneyFormatter.formatCrypto(share.amount, symbol),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        share.value?.let {
            com.altaran.watchwallet.core.designsystem.component.AmountText(MoneyFormatter.format(it, currency), style = MaterialTheme.typography.titleSmall)
        }
    }
}
