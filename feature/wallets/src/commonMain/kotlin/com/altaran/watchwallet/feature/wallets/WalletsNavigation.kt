package com.altaran.watchwallet.feature.wallets

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

@Serializable
data object WalletsRoute

@Serializable
data class WalletDetailRoute(val walletId: Long)

fun NavGraphBuilder.walletsScreen(onAddWallet: () -> Unit, onOpenWallet: (Long) -> Unit) {
    composable<WalletsRoute> { WalletsRoute(onAddWallet = onAddWallet, onOpenWallet = onOpenWallet) }
}

fun NavGraphBuilder.walletDetailScreen(onNavigateUp: () -> Unit, onOpenHolding: (String) -> Unit) {
    composable<WalletDetailRoute> { entry ->
        val route = entry.toRoute<WalletDetailRoute>()
        WalletDetailRoute(walletId = route.walletId, onNavigateUp = onNavigateUp, onOpenHolding = onOpenHolding)
    }
}

val walletsModule = module {
    viewModelOf(::WalletsViewModel)
    viewModel { params -> WalletDetailViewModel(params.get(), get(), get(), get(), get(), get(), get()) }
}
