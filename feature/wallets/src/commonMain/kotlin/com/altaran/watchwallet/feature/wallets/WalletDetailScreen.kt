package com.altaran.watchwallet.feature.wallets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altaran.watchwallet.core.data.repository.CostBasisEntry
import com.altaran.watchwallet.core.data.repository.CostKey
import com.altaran.watchwallet.core.data.repository.Holding
import com.altaran.watchwallet.core.designsystem.component.AmountText
import com.altaran.watchwallet.core.designsystem.component.AssetRow
import com.altaran.watchwallet.core.designsystem.component.ChainBadge
import com.altaran.watchwallet.core.designsystem.component.CryptoIcons
import com.altaran.watchwallet.core.designsystem.component.EmptyState
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.designsystem.theme.WatchWalletTheme
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.TxDirection
import com.altaran.watchwallet.core.model.WalletTransaction
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.ui.TransactionDetails
import com.altaran.watchwallet.core.ui.TransactionRow
import com.altaran.watchwallet.core.ui.TxView
import com.altaran.watchwallet.core.ui.ValueChartSection
import com.altaran.watchwallet.core.ui.chartFormatFor
import com.altaran.watchwallet.core.ui.clipboard.plainTextClipEntry
import com.altaran.watchwallet.core.ui.format.DateFormatter
import com.altaran.watchwallet.core.ui.format.MoneyFormatter
import com.altaran.watchwallet.core.ui.shortAddress
import com.altaran.watchwallet.core.ui.targetDescription
import com.altaran.watchwallet.feature.wallets.generated.resources.*
import com.altaran.watchwallet.feature.wallets.generated.resources.Res
import com.altaran.watchwallet.feature.wallets.generated.resources.cost_dialog_cancel
import com.altaran.watchwallet.feature.wallets.generated.resources.cost_dialog_label
import com.altaran.watchwallet.feature.wallets.generated.resources.cost_dialog_label_per_coin
import com.altaran.watchwallet.feature.wallets.generated.resources.cost_dialog_message
import com.altaran.watchwallet.feature.wallets.generated.resources.cost_dialog_per_coin
import com.altaran.watchwallet.feature.wallets.generated.resources.cost_dialog_per_coin_hint
import com.altaran.watchwallet.feature.wallets.generated.resources.cost_dialog_reset
import com.altaran.watchwallet.feature.wallets.generated.resources.cost_dialog_save
import com.altaran.watchwallet.feature.wallets.generated.resources.cost_dialog_title
import com.altaran.watchwallet.feature.wallets.generated.resources.cost_dialog_total
import com.altaran.watchwallet.feature.wallets.generated.resources.cost_dialog_total_hint
import com.altaran.watchwallet.feature.wallets.generated.resources.detail_open_explorer
import com.altaran.watchwallet.feature.wallets.generated.resources.tx_cost_auto
import com.altaran.watchwallet.feature.wallets.generated.resources.tx_cost_unknown
import com.altaran.watchwallet.feature.wallets.generated.resources.tx_cost_user
import com.altaran.watchwallet.feature.wallets.generated.resources.tx_sheet_cost_auto
import com.altaran.watchwallet.feature.wallets.generated.resources.tx_sheet_cost_user
import com.altaran.watchwallet.feature.wallets.generated.resources.tx_sheet_edit_cost
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun WalletDetailRoute(
    walletId: Long,
    onNavigateUp: () -> Unit,
    onOpenHolding: (String) -> Unit,
    viewModel: WalletDetailViewModel = koinViewModel(key = "wallet-$walletId") { parametersOf(walletId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted, state.wallet) { if (state.deleted && state.wallet == null && state.value != null) onNavigateUp() }
    WalletDetailScreen(
        state = state,
        chain = viewModel::chain,
        onNavigateUp = onNavigateUp,
        onRefresh = viewModel::refresh,
        onRangeChange = viewModel::setRange,
        onRename = viewModel::rename,
        onSetCost = viewModel::setCost,
        onDelete = {
            viewModel.delete()
            onNavigateUp()
        },
        onOpenHolding = onOpenHolding,
    )
}

private enum class DetailTab { ASSETS, ACTIVITY, ADDRESSES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletDetailScreen(
    state: WalletDetailUiState,
    chain: (ChainId) -> Chain?,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onRangeChange: (com.altaran.watchwallet.core.model.ChartRange) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onOpenHolding: (String) -> Unit,
    onSetCost: (TxView, Double?) -> Unit = { _, _ -> },
) {
    var selectedTx by remember { mutableStateOf<TxView?>(null) }
    val sheetUriHandler = LocalUriHandler.current
    TransactionDetails(selectedTx, onSelect = { selectedTx = it }, onSetCost = onSetCost, onOpenExplorer = { sheetUriHandler.openUri(it) })
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val uriHandler = LocalUriHandler.current
    val wallet = state.wallet

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(wallet?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) { Icon(WwIcon.ArrowBack.painter(), stringResource(Res.string.detail_back)) }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(WwIcon.Settings.painter(), stringResource(Res.string.detail_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.detail_rename)) }, onClick = {
                            menuOpen = false
                            renameOpen = true
                        })
                        explorerUrl(state, chain)?.let { url ->
                            DropdownMenuItem(text = { Text(stringResource(Res.string.detail_open_explorer)) }, onClick = {
                                menuOpen = false
                                uriHandler.openUri(url)
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(Res.string.detail_delete)) }, onClick = {
                            menuOpen = false
                            deleteOpen = true
                        })
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.syncing, onRefresh = onRefresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(bottom = 8.dp)) {
                        wallet?.target?.let { target ->
                            Text(
                                targetDescription(target),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        ActiveChains(state, chain)
                        if (state.failed) {
                            Text(stringResource(Res.string.wallets_sync_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                        }
                        Spacer(Modifier.size(8.dp))
                        ValueChartSection(
                            label = stringResource(Res.string.detail_balance),
                            currentValue = state.value,
                            points = state.chart,
                            range = state.range,
                            onRangeChange = onRangeChange,
                            format = { MoneyFormatter.format(it, state.currency) },
                            formatChange = { MoneyFormatter.formatChange(it, state.currency) },
                            numberFormat = chartFormatFor(state.currency),
                            markerTimes = state.transactions.map { it.timestamp.toEpochMilliseconds() },
                        )
                    }
                }
                item {
                    val tabs = buildList {
                        add(DetailTab.ASSETS)
                        add(DetailTab.ACTIVITY)
                        if (wallet?.target is WatchTarget.ExtendedPublicKey) add(DetailTab.ADDRESSES)
                    }
                    if (tab >= tabs.size) tab = 0
                    PrimaryTabRow(selectedTabIndex = tab) {
                        tabs.forEachIndexed { index, item ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = {
                                Text(
                                    stringResource(
                                        when (item) {
                                            DetailTab.ASSETS -> Res.string.detail_tab_assets
                                            DetailTab.ACTIVITY -> Res.string.detail_tab_activity
                                            DetailTab.ADDRESSES -> Res.string.detail_tab_addresses
                                        },
                                    ),
                                )
                            })
                        }
                    }
                }
                val tabs = listOf(DetailTab.ASSETS, DetailTab.ACTIVITY, DetailTab.ADDRESSES)
                when (tabs.getOrNull(tab)) {
                    DetailTab.ASSETS -> holdings(state, onOpenHolding)
                    DetailTab.ACTIVITY -> activity(state, chain) { tx -> selectedTx = tx }
                    DetailTab.ADDRESSES -> addresses(state)
                    null -> Unit
                }
            }
        }
    }

    if (renameOpen && wallet != null) {
        RenameDialog(wallet.name, onDismiss = { renameOpen = false }) {
            renameOpen = false
            onRename(it)
        }
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(stringResource(Res.string.detail_delete_title)) },
            text = { Text(stringResource(Res.string.detail_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    onDelete()
                }) { Text(stringResource(Res.string.detail_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text(stringResource(Res.string.detail_cancel)) } },
        )
    }
}

@Composable
private fun ActiveChains(state: WalletDetailUiState, chain: (ChainId) -> Chain?) {
    val chains = state.wallet?.activeChains.orEmpty().mapNotNull(chain)
    if (chains.size <= 1) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chains.forEach { ChainBadge(it.name, CryptoIcons.forChain(it.id.value)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.holdings(state: WalletDetailUiState, onOpen: (String) -> Unit) {
    if (state.holdings.isEmpty()) {
        item { EmptyState(WwIcon.Wallets, stringResource0(Res.string.detail_no_assets_title), stringResource0(Res.string.detail_no_assets_message)) }
        return
    }
    items(state.holdings, key = { "h-" + it.key }) { holding -> HoldingRow(holding, state, onOpen) }
}

@Composable
private fun stringResource0(res: org.jetbrains.compose.resources.StringResource): String = stringResource(res)

@Composable
internal fun HoldingRow(holding: Holding, state: WalletDetailUiState, onOpen: (String) -> Unit) {
    AssetRow(
        iconKey = CryptoIcons.forSymbol(holding.symbol),
        symbol = holding.symbol,
        subtitle = MoneyFormatter.formatCrypto(holding.amount, holding.symbol),
        value = holding.value?.let { MoneyFormatter.format(it, state.currency) },
        change = holding.change24hPercent?.let { MoneyFormatter.formatPercentChange(it / 100) },
        changeDirection = holding.change24hPercent?.compareTo(0.0) ?: 0,
        onClick = { onOpen(holding.key) },
        iconUrl = com.altaran.watchwallet.core.data.AssetIcons.url(holding.priceId),
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.activity(
    state: WalletDetailUiState,
    chain: (ChainId) -> Chain?,
    onOpen: (TxView) -> Unit,
) {
    if (state.transactions.isEmpty()) {
        item { EmptyState(WwIcon.Markets, stringResource0(Res.string.detail_no_activity_title), stringResource0(Res.string.detail_no_activity_message)) }
        return
    }
    items(state.transactions, key = { "t-" + it.hash + it.assetId.value }) { tx ->
        val view = tx.toView(state, chain(tx.chainId))
        TransactionRow(view) { onOpen(view) }
        HorizontalDivider(Modifier.padding(start = 16.dp))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.addresses(state: WalletDetailUiState) {
    val next = state.derivedAddresses.firstOrNull { !it.change && !it.used }
    if (next != null) {
        item { NextAddressCard(next.address) }
    }
    items(state.derivedAddresses, key = { "a-" + it.address }) { address ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (address.change) "1/" else "0/") + address.index,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(56.dp),
            )
            Text(
                shortAddress(address.address, 12, 10),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource0(if (address.used) Res.string.address_used else Res.string.address_unused),
                style = MaterialTheme.typography.labelMedium,
                color = if (address.used) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NextAddressCard(address: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    androidx.compose.material3.OutlinedCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.address_next_receive), style = MaterialTheme.typography.titleSmall)
            Text(address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                scope.launch {
                    clipboard.setClipEntry(plainTextClipEntry(address, sensitive = true))
                    copied = true
                }
            }) {
                Icon(if (copied) WwIcon.Check.painter() else WwIcon.Copy.painter(), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (copied) Res.string.address_copied else Res.string.address_copy))
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.detail_rename)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.detail_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.detail_cancel)) } },
    )
}

private fun explorerUrl(state: WalletDetailUiState, chain: (ChainId) -> Chain?): String? {
    val target = state.wallet?.target as? WatchTarget.Address ?: return null
    return chain(target.chainId)?.explorerAddressUrl?.replace("{address}", target.address)
}

/** Display model of a transaction of this wallet (shared row/sheet in core:ui). */
private fun WalletTransaction.toView(state: WalletDetailUiState, chain: Chain?): TxView {
    val asset = state.assets[assetId]
    val cost = state.costs[CostKey(walletId, hash, assetId.value)]
    return TxView(
        walletId = walletId,
        hash = hash,
        assetId = assetId.value,
        direction = direction,
        confirmed = confirmed,
        time = timestamp,
        amount = asset?.let { TokenAmount(delta, it.decimals).toDouble() } ?: 0.0,
        symbol = asset?.symbol ?: "?",
        chainName = chain?.name,
        walletName = null,
        costUsd = cost?.valueUsd,
        costUserSet = cost?.userSet == true,
        explorerUrl = chain?.explorerTxUrl?.replace("{tx}", hash),
    )
}
