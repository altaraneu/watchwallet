package com.altaran.watchwallet.core.database

import org.koin.core.module.Module
import org.koin.dsl.module

/** Provides the platform-specific [WatchWalletDatabase] builder. */
internal expect fun Module.databaseBuilder(): Module

val databaseModule: Module = module {
    databaseBuilder()
    single { get<WatchWalletDatabase>().walletDao() }
    single { get<WatchWalletDatabase>().priceDao() }
}
