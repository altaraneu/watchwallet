package com.altaran.watchwallet.feature.portfolio

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

@Serializable
data object PortfolioRoute

fun NavGraphBuilder.portfolioScreen(onAddWallet: () -> Unit) {
    composable<PortfolioRoute> {
        PortfolioRoute(onAddWallet = onAddWallet)
    }
}

val portfolioModule = module {
    viewModelOf(::PortfolioViewModel)
}
