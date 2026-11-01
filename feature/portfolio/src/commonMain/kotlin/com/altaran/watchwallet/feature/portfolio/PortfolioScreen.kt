package com.altaran.watchwallet.feature.portfolio

import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altaran.watchwallet.core.data.repository.Holding
import com.altaran.watchwallet.core.designsystem.chart.AllocationPalette
import com.altaran.watchwallet.core.designsystem.chart.DonutChart
import com.altaran.watchwallet.core.designsystem.chart.DonutSlice
import com.altaran.watchwallet.core.designsystem.component.AssetRow
import com.altaran.watchwallet.core.designsystem.component.CryptoIcons
import com.altaran.watchwallet.core.designsystem.component.EmptyState
import com.altaran.watchwallet.core.designsystem.component.SectionHeader
import com.altaran.watchwallet.core.designsystem.component.SkeletonBlock
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.ui.ValueChartSection
import com.altaran.watchwallet.core.ui.chartFormatFor
import com.altaran.watchwallet.core.ui.format.MoneyFormatter
import com.altaran.watchwallet.feature.portfolio.generated.resources.*
import com.altaran.watchwallet.feature.portfolio.generated.resources.Res
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
        onRefresh = viewModel::refresh,
        onOpenHolding = onOpenHolding,
        onSelectGroup = viewModel::selectGroup,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortfolioScreen(
    uiState: PortfolioUiState,
    onToggleDiscreetMode: (current: Boolean) -> Unit,
    onAddWallet: () -> Unit,
    onRangeChange: (ChartRange) -> Unit,
    onRefresh: () -> Unit,
    onOpenHolding: (String) -> Unit,
    onSelectGroup: (Long?) -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.portfolio_title)) },
                actions = {
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
                    PortfolioContent(uiState, onRangeChange, onOpenHolding, onSelectGroup)
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
) {
    val currency = state.quoteCurrency
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
        item { SectionHeader(stringResource(Res.string.portfolio_assets)) }
        items(snapshot.holdings, key = { it.key }) { holding -> HoldingRow(holding, currency, onOpenHolding) }
        if (snapshot.hiddenAssetCount > 0) {
            item {
                Text(
                    pluralStringResource(Res.plurals.portfolio_hidden_assets, snapshot.hiddenAssetCount, snapshot.hiddenAssetCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
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
private fun HoldingRow(holding: Holding, currency: QuoteCurrency, onOpen: (String) -> Unit) {
    AssetRow(
        iconKey = CryptoIcons.forSymbol(holding.symbol),
        symbol = holding.symbol,
        subtitle = MoneyFormatter.formatCrypto(holding.amount, holding.symbol),
        value = holding.value?.let { MoneyFormatter.format(it, currency) },
        change = holding.change24hPercent?.let { MoneyFormatter.formatPercentChange(it / 100) },
        changeDirection = holding.change24hPercent?.compareTo(0.0) ?: 0,
        onClick = { onOpen(holding.key) },
    )
}

private const val MAX_SLICES = 6

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
            slices = entries.mapIndexed { i, (_, value) -> DonutSlice(value, AllocationPalette[i % AllocationPalette.size]) },
            modifier = Modifier.size(140.dp),
        )
        Spacer(Modifier.width(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.forEachIndexed { i, (label, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(AllocationPalette[i % AllocationPalette.size]))
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
                    Text(MoneyFormatter.formatPercent(value / total), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
