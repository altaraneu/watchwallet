package com.altaran.watchwallet.core.data

import com.altaran.watchwallet.core.data.repository.DefaultSettingsRepository
import com.altaran.watchwallet.core.data.repository.DefaultWalletRepository
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.database.databaseModule
import com.altaran.watchwallet.core.datastore.dataStoreModule
import org.koin.core.module.Module
import org.koin.dsl.module

val dataModule: Module = module {
    includes(dataStoreModule, databaseModule)
    single<SettingsRepository> { DefaultSettingsRepository(get()) }
    single<WalletRepository> { DefaultWalletRepository(get()) }
}
