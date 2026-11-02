package com.altaran.watchwallet.feature.wallets

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altaran.watchwallet.core.data.sync.WalletSyncHealth
import com.altaran.watchwallet.core.designsystem.component.AmountText
import com.altaran.watchwallet.core.designsystem.component.CryptoIcon
import com.altaran.watchwallet.core.designsystem.component.CryptoIcons
import com.altaran.watchwallet.core.designsystem.component.EmptyState
import com.altaran.watchwallet.core.designsystem.component.PoweredByCoinGecko
import com.altaran.watchwallet.core.designsystem.component.SectionHeader
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.ui.format.MoneyFormatter
import com.altaran.watchwallet.core.ui.targetDescription
import com.altaran.watchwallet.feature.wallets.generated.resources.*
import com.altaran.watchwallet.feature.wallets.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun WalletsRoute(
    onAddWallet: () -> Unit,
    onOpenWallet: (Long) -> Unit,
    viewModel: WalletsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WalletsScreen(state, onAddWallet, onOpenWallet, viewModel::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletsScreen(
    state: WalletsUiState,
    onAddWallet: () -> Unit,
    onOpenWallet: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(stringResource(Res.string.wallets_title)) }, scrollBehavior = scrollBehavior) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddWallet,
                icon = { Icon(WwIcon.Add.painter(), contentDescription = null) },
                text = { Text(stringResource(Res.string.wallets_add)) },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!state.loading && state.items.isEmpty()) {
                EmptyState(
                    icon = WwIcon.Wallets,
                    title = stringResource(Res.string.wallets_empty_title),
                    message = stringResource(Res.string.wallets_empty_message),
                    actionLabel = stringResource(Res.string.wallets_add),
                    onAction = onAddWallet,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    val sections = state.items.groupBy { it.wallet.groupId?.takeIf { id -> id in state.groups } }
                        .entries.sortedBy { (groupId, _) -> if (groupId == null) Long.MAX_VALUE else groupId }
                    sections.forEach { (groupId, sectionItems) ->
                        if (state.groups.isNotEmpty()) {
                            item(key = "g-$groupId") {
                                SectionHeader(groupId?.let { state.groups.getValue(it) } ?: stringResource(Res.string.wallets_ungrouped))
                            }
                        }
                        items(sectionItems, key = { it.wallet.id }) { item ->
                            WalletRow(item, state.currency, onClick = { onOpenWallet(item.wallet.id) })
                            HorizontalDivider(Modifier.padding(start = 72.dp))
                        }
                    }
                    if (state.poweredByCoinGecko) item { PoweredByCoinGecko(Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun WalletRow(item: WalletListItem, currency: QuoteCurrency, onClick: () -> Unit) {
    val target = item.wallet.target
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CryptoIcon(
            iconKey = when (target) {
                is com.altaran.watchwallet.core.model.WatchTarget.Address -> CryptoIcons.forChain(target.chainId.value)
                is com.altaran.watchwallet.core.model.WatchTarget.ExtendedPublicKey -> CryptoIcons.forChain(target.chainId.value)
                null -> null
            },
            fallbackText = item.wallet.name,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.wallet.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (target != null) {
                Text(targetDescription(target), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            SyncHealthHint(item.syncHealth)
        }
        if (item.syncing) {
            val syncingDescription = stringResource(Res.string.wallets_syncing)
            CircularProgressIndicator(
                Modifier.size(16.dp).semantics { contentDescription = syncingDescription },
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(12.dp))
        }
        AmountText(item.value?.let { MoneyFormatter.format(it, currency) } ?: "—", style = MaterialTheme.typography.titleSmall)
    }
}

/** Nothing while all is well; only a sync that really failed reads as an error. */
@Composable
internal fun SyncHealthHint(health: WalletSyncHealth, modifier: Modifier = Modifier) {
    val text = when (health) {
        WalletSyncHealth.OK -> return
        WalletSyncHealth.NATIVE_ONLY -> Res.string.wallets_sync_native_only
        WalletSyncHealth.PARTIAL -> Res.string.wallets_sync_partial
        WalletSyncHealth.FAILED -> Res.string.wallets_sync_failed
    }
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = if (health == WalletSyncHealth.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
