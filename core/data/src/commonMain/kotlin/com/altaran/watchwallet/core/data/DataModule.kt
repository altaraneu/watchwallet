package com.altaran.watchwallet.core.data

import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.bitcoin.BitcoinAdapter
import com.altaran.watchwallet.chain.evm.EvmAdapter
import com.altaran.watchwallet.core.data.chain.ChainRegistry
import com.altaran.watchwallet.core.data.repository.DefaultSettingsRepository
import com.altaran.watchwallet.core.data.repository.DefaultWalletRepository
import com.altaran.watchwallet.core.data.repository.MarketDataRepository
import com.altaran.watchwallet.core.data.repository.PortfolioRepository
import com.altaran.watchwallet.core.data.repository.PriceRepository
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.database.databaseModule
import com.altaran.watchwallet.core.datastore.dataStoreModule
import com.altaran.watchwallet.core.network.createHttpClient
import com.altaran.watchwallet.core.pricing.CoinGeckoProvider
import com.altaran.watchwallet.core.pricing.FxRates
import com.altaran.watchwallet.core.pricing.KrakenMarketData
import com.altaran.watchwallet.core.pricing.PriceProvider
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.dsl.module

val dataModule: Module = module {
    includes(dataStoreModule, databaseModule)

    single<HttpClient> { createHttpClient() }
    single<List<ChainAdapter>> { listOf(BitcoinAdapter(get()), EvmAdapter(get())) }
    single { ChainRegistry(get()) }
    single<PriceProvider> { CoinGeckoProvider(get()) }
    single { KrakenMarketData(get()) }
    single { FxRates(get()) }
    single { MarketDataRepository(get(), get()) }

    single<SettingsRepository> { DefaultSettingsRepository(get()) }
    single<WalletRepository> { DefaultWalletRepository(get()) }
    single { PriceRepository(get(), get(), get(), get()) }
    single { PortfolioRepository(get(), get()) }
    single { SyncEngine(get(), get(), get()) }
    single { AddWalletService(get(), get(), get()) }
    single { DemoWallets(get(), get()) }
}
