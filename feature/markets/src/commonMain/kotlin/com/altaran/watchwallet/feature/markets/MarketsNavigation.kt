package com.altaran.watchwallet.feature.markets

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

@Serializable
data object MarketsRoute

/** Detail of one asset; [key] is a price id (`bitcoin`) or an asset id for unpriced tokens. */
@Serializable
data class AssetDetailRoute(val key: String)

fun NavGraphBuilder.marketsScreen(onOpenAsset: (String) -> Unit) {
    composable<MarketsRoute> { MarketsRoute(onOpenAsset = onOpenAsset) }
}

fun NavGraphBuilder.assetDetailScreen(onNavigateUp: () -> Unit) {
    composable<AssetDetailRoute> { entry ->
        AssetDetailRoute(key = entry.toRoute<AssetDetailRoute>().key, onNavigateUp = onNavigateUp)
    }
}

val marketsModule = module {
    viewModelOf(::MarketsViewModel)
    viewModel { params -> AssetDetailViewModel(params.get(), get(), get(), get(), get(), get(), get(), get()) }
}
