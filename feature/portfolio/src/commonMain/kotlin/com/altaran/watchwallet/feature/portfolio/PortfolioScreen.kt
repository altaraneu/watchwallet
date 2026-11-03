package com.altaran.watchwallet.feature.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altaran.watchwallet.core.data.repository.HiddenAsset
import com.altaran.watchwallet.core.data.repository.Holding
import com.altaran.watchwallet.core.designsystem.chart.AllocationPalette
import com.altaran.watchwallet.core.designsystem.chart.DonutChart
import com.altaran.watchwallet.core.designsystem.chart.DonutSlice
import com.altaran.watchwallet.core.designsystem.component.AmountText
import com.altaran.watchwallet.core.designsystem.component.AssetRow
import com.altaran.watchwallet.core.designsystem.component.CryptoIcons
import com.altaran.watchwallet.core.designsystem.component.EmptyState
import com.altaran.watchwallet.core.designsystem.component.LocalDiscreetMode
import com.altaran.watchwallet.core.designsystem.component.PoweredByCoinGecko
import com.altaran.watchwallet.core.designsystem.component.SectionHeader
import com.altaran.watchwallet.core.designsystem.component.SkeletonBlock
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.ui.ValueChartSection
import com.altaran.watchwallet.core.ui.chartFormatFor
import com.altaran.watchwallet.core.ui.format.MoneyFormatter
import com.altaran.watchwallet.feature.portfolio.generated.resources.*
import com.altaran.watchwallet.feature.portfolio.generated.resources.Res
import com.altaran.watchwallet.feature.portfolio.generated.resources.portfolio_add_wallet
import com.altaran.watchwallet.feature.portfolio.generated.resources.portfolio_hidden_reason_other
import com.altaran.watchwallet.feature.portfolio.generated.resources.portfolio_hidden_reason_spam
import com.altaran.watchwallet.feature.portfolio.generated.resources.portfolio_hide_asset
import com.altaran.watchwallet.feature.portfolio.generated.resources.portfolio_show_asset
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun PortfolioRoute(
    onAddWallet: () -> Unit,
    onOpenHolding: (String) -> Unit,
    viewModel: PortfolioViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PortfolioScreen(
        uiState = uiState,
        onToggleDiscreetMode = viewModel::onToggleDiscreetMode,
        onAddWallet = onAddWallet,
        onRangeChange = viewModel::setRange,
        onVisibleWindow = viewModel::onVisibleWindow,
        onRefresh = viewModel::refresh,
        onOpenHolding = onOpenHolding,
        onSelectGroup = viewModel::selectGroup,
        onSetVisibility = viewModel::setVisibility,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortfolioScreen(
    uiState: PortfolioUiState,
    onToggleDiscreetMode: (current: Boolean) -> Unit,
    onAddWallet: () -> Unit,
    onRangeChange: (ChartRange) -> Unit,
    onVisibleWindow: (Long, Long) -> Unit = { _, _ -> },
    onRefresh: () -> Unit,
    onOpenHolding: (String) -> Unit,
    onSelectGroup: (Long?) -> Unit = {},
    onSetVisibility: (List<Asset>, Boolean) -> Unit = { _, _ -> },
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.portfolio_title)) },
                actions = {
                    IconButton(onClick = onAddWallet) {
                        Icon(WwIcon.Add.painter(), contentDescription = stringResource(Res.string.portfolio_add_wallet))
                    }
                    if (uiState is PortfolioUiState.Ready) {
                        IconButton(onClick = { onToggleDiscreetMode(uiState.discreetMode) }) {
                            Icon(
                                painter = (if (uiState.discreetMode) WwIcon.VisibilityOff else WwIcon.Visibility).painter(),
                                contentDescription = stringResource(
                                    if (uiState.discreetMode) Res.string.portfolio_discreet_off else Res.string.portfolio_discreet_on,
                                ),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when (uiState) {
            PortfolioUiState.Loading -> Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBlock(width = 96.dp, height = 16.dp)
                SkeletonBlock(width = 220.dp, height = 40.dp)
            }

            is PortfolioUiState.Ready -> PullToRefreshBox(
                isRefreshing = uiState.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                if (uiState.walletCount == 0) {
                    EmptyState(
                        icon = WwIcon.Wallets,
                        title = stringResource(Res.string.portfolio_empty_title),
                        message = stringResource(Res.string.portfolio_empty_message),
                        actionLabel = stringResource(Res.string.portfolio_empty_action),
                        onAction = onAddWallet,
                    )
                } else {
                    PortfolioContent(uiState, onRangeChange, onOpenHolding, onSelectGroup, onSetVisibility, onVisibleWindow)
                }
            }
        }
    }
}

@Composable
private fun PortfolioContent(
    state: PortfolioUiState.Ready,
    onRangeChange: (ChartRange) -> Unit,
    onOpenHolding: (String) -> Unit,
    onSelectGroup: (Long?) -> Unit,
    onSetVisibility: (List<Asset>, Boolean) -> Unit,
    onVisibleWindow: (Long, Long) -> Unit,
) {
    val currency = state.quoteCurrency
    var hiddenExpanded by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.snapshot
    LazyColumn(Modifier.fillMaxSize()) {
        if (state.groups.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = state.selectedGroupId == null, onClick = {
                        onSelectGroup(null)
                    }, label = { Text(stringResource(Res.string.portfolio_all_wallets)) })
                    state.groups.forEach { group ->
                        FilterChip(selected = state.selectedGroupId == group.id, onClick = { onSelectGroup(group.id) }, label = { Text(group.name) })
                    }
                }
            }
        }
        item {
            ValueChartSection(
                label = stringResource(Res.string.portfolio_total_value),
                currentValue = snapshot.totalValue,
                points = state.chart,
                range = state.range,
                onRangeChange = onRangeChange,
                onVisibleWindow = onVisibleWindow,
                format = { MoneyFormatter.format(it, currency) },
                formatChange = { MoneyFormatter.formatChange(it, currency) },
                numberFormat = chartFormatFor(currency),
                secondaryLine = snapshot.change24hPercent?.let {
                    stringResource(
                        Res.string.portfolio_change_24h,
                        MoneyFormatter.formatChange(snapshot.change24hValue, currency),
                        MoneyFormatter.formatPercentChange(it / 100),
                    )
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (state.poweredByCoinGecko) item { PoweredByCoinGecko(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        item { SectionHeader(stringResource(Res.string.portfolio_assets)) }
        items(snapshot.holdings, key = { it.key }) { holding ->
            HoldingRow(holding, currency, onOpenHolding, onHide = { onSetVisibility(holding.assets, false) })
        }
        if (snapshot.hiddenAssetCount > 0) {
            item {
                // Tap to review hidden tokens and show any of them again.
                val toggleState = stringResource(
                    if (hiddenExpanded) Res.string.portfolio_hidden_expanded else Res.string.portfolio_hidden_collapsed,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { hiddenExpanded = !hiddenExpanded }
                        .semantics { stateDescription = toggleState }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        pluralStringResource(Res.plurals.portfolio_hidden_assets, snapshot.hiddenAssetCount, snapshot.hiddenAssetCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Decorative: the expanded state is announced through stateDescription.
                    Text(
                        if (hiddenExpanded) "▲" else "▼",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp).clearAndSetSemantics {},
                    )
                }
            }
            if (hiddenExpanded) {
                items(snapshot.hiddenAssets, key = { "hidden-" + it.key }) { hidden ->
                    HiddenAssetRow(hidden, onShow = { onSetVisibility(hidden.assets, true) })
                }
            }
        }
        if (snapshot.holdings.count { (it.value ?: 0.0) > 0 } >= 2) {
            item { SectionHeader(stringResource(Res.string.portfolio_allocation)) }
            item { Allocation(snapshot.holdings, snapshot.totalValue) }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
private fun HoldingRow(holding: Holding, currency: QuoteCurrency, onOpen: (String) -> Unit, onHide: () -> Unit) {
    // Native coins (BTC, ETH…) cannot be hidden; tokens can, with a long press.
    val hideable = holding.assets.any { it.id != AssetId.native(it.chainId) }
    var menu by remember { mutableStateOf(false) }
    Box {
        HoldingAssetRow(holding, currency, onOpen, onLongClick = if (hideable) ({ menu = true }) else null)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.portfolio_hide_asset)) }, onClick = {
                menu = false
                onHide()
            })
        }
    }
}

@Composable
private fun HiddenAssetRow(hidden: HiddenAsset, onShow: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(hidden.symbol, style = MaterialTheme.typography.bodyLarge)
            val reason = stringResource(
                if (hidden.isSpam) Res.string.portfolio_hidden_reason_spam else Res.string.portfolio_hidden_reason_other,
                MoneyFormatter.formatCrypto(hidden.amount, hidden.symbol),
            )
            if (LocalDiscreetMode.current) {
                // AmountText hides it; the sentence would not fit its single line anyway.
                AmountText(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onShow) { Text(stringResource(Res.string.portfolio_show_asset)) }
    }
}

@Composable
private fun HoldingAssetRow(holding: Holding, currency: QuoteCurrency, onOpen: (String) -> Unit, onLongClick: (() -> Unit)?) {
    AssetRow(
        iconKey = CryptoIcons.forPriceId(holding.priceId),
        symbol = holding.symbol,
        subtitle = MoneyFormatter.formatCrypto(holding.amount, holding.symbol),
        value = holding.value?.let { MoneyFormatter.format(it, currency) },
        change = holding.change24hPercent?.let { MoneyFormatter.formatPercentChange(it / 100) },
        changeDirection = holding.change24hPercent?.compareTo(0.0) ?: 0,
        onClick = { onOpen(holding.key) },
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(Res.string.portfolio_hide_asset),
        iconUrl = com.altaran.watchwallet.core.data.AssetIcons.url(holding.priceId),
    )
}

private const val MAX_SLICES = 6

/** Equal slices in discreet mode: their sizes would show the proportions the hidden percentages do. */
internal fun donutValues(values: List<Double>, discreet: Boolean): List<Double> = if (discreet) values.map { 1.0 } else values

@Composable
private fun Allocation(holdings: List<Holding>, total: Double) {
    if (total <= 0) return
    val priced = holdings.filter { (it.value ?: 0.0) > 0 }
    val top = priced.take(MAX_SLICES)
    val otherValue = priced.drop(MAX_SLICES).sumOf { it.value ?: 0.0 }
    val otherLabel = stringResource(Res.string.portfolio_other)
    val entries = top.map { it.symbol to (it.value ?: 0.0) } + if (otherValue > 0) listOf(otherLabel to otherValue) else emptyList()
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        DonutChart(
            slices = donutValues(entries.map { it.second }, LocalDiscreetMode.current).mapIndexed { i, value ->
                DonutSlice(value, AllocationPalette[i % AllocationPalette.size])
            },
            modifier = Modifier.size(140.dp),
        )
        Spacer(Modifier.width(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.forEachIndexed { i, (label, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(AllocationPalette[i % AllocationPalette.size]))
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
                    AmountText(MoneyFormatter.formatPercent(value / total), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
