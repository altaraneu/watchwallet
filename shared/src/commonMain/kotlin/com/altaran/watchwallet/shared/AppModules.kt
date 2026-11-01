package com.altaran.watchwallet.shared

import com.altaran.watchwallet.core.data.dataModule
import com.altaran.watchwallet.feature.addwallet.addWalletModule
import com.altaran.watchwallet.feature.markets.marketsModule
import com.altaran.watchwallet.feature.portfolio.portfolioModule
import com.altaran.watchwallet.feature.settings.settingsModule
import com.altaran.watchwallet.feature.wallets.walletsModule
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

private val appModule = module {
    viewModelOf(::AppViewModel)
}

/**
 * All shared Koin modules. The platform shell adds its own module with platform services
 * (`AppLanguageController`, `AppInfo`, Android context…).
 */
val sharedModules: List<Module> = listOf(
    appModule,
    dataModule,
    portfolioModule,
    walletsModule,
    addWalletModule,
    marketsModule,
    settingsModule,
)
