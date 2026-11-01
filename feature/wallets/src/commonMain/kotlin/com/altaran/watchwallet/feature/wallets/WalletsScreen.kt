package com.altaran.watchwallet.feature.wallets

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.designsystem.component.EmptyState
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.model.Wallet
import com.altaran.watchwallet.feature.wallets.generated.resources.Res
import com.altaran.watchwallet.feature.wallets.generated.resources.wallets_empty_message
import com.altaran.watchwallet.feature.wallets.generated.resources.wallets_empty_title
import com.altaran.watchwallet.feature.wallets.generated.resources.wallets_title
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

@Serializable
data object WalletsRoute

fun NavGraphBuilder.walletsScreen() {
    composable<WalletsRoute> { WalletsRoute() }
}

val walletsModule = module {
    viewModelOf(::WalletsViewModel)
}

internal class WalletsViewModel(walletRepository: WalletRepository) : ViewModel() {
    val wallets: StateFlow<ImmutableList<Wallet>> = walletRepository.activeWallets
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())
}

@Composable
private fun WalletsRoute(viewModel: WalletsViewModel = koinViewModel()) {
    val wallets by viewModel.wallets.collectAsStateWithLifecycle()
    WalletsScreen(wallets = wallets)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletsScreen(wallets: ImmutableList<Wallet>) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.wallets_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (wallets.isEmpty()) {
            EmptyState(
                icon = WwIcon.Wallets,
                title = stringResource(Res.string.wallets_empty_title),
                message = stringResource(Res.string.wallets_empty_message),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(wallets, key = Wallet::id) { wallet ->
                    ListItem(
                        headlineContent = { Text(wallet.name) },
                        leadingContent = wallet.emoji?.let { { Text(it) } },
                    )
                }
            }
        }
    }
}
