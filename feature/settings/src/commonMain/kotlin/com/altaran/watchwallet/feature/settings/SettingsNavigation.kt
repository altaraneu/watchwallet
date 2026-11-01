package com.altaran.watchwallet.feature.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

@Serializable
data object SettingsRoute

fun NavGraphBuilder.settingsScreen(onOpenSupport: () -> Unit) {
    composable<SettingsRoute> {
        SettingsRoute(onOpenSupport = onOpenSupport)
    }
}

val settingsModule = module {
    viewModelOf(::SettingsViewModel)
}
