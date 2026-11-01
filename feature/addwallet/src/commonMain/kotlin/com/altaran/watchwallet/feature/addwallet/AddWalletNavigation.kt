package com.altaran.watchwallet.feature.addwallet

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

@Serializable
data object AddWalletRoute

fun NavGraphBuilder.addWalletScreen(onNavigateUp: () -> Unit, onWalletAdded: (Long) -> Unit) {
    composable<AddWalletRoute> {
        AddWalletRoute(onNavigateUp = onNavigateUp, onWalletAdded = onWalletAdded)
    }
}

val addWalletModule = module {
    viewModelOf(::AddWalletViewModel)
}
