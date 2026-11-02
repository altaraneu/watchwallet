package com.altaran.watchwallet.core.data

import com.altaran.watchwallet.chain.algorand.AlgorandAdapter
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.aptos.AptosAdapter
import com.altaran.watchwallet.chain.bitcoin.BitcoinAdapter
import com.altaran.watchwallet.chain.bitcoin.BitcoinChain
import com.altaran.watchwallet.chain.bitcoincash.BitcoinCashAdapter
import com.altaran.watchwallet.chain.cardano.CardanoAdapter
import com.altaran.watchwallet.chain.cosmos.CosmosAdapter
import com.altaran.watchwallet.chain.dogecoin.DogecoinAdapter
import com.altaran.watchwallet.chain.evm.EvmAdapter
import com.altaran.watchwallet.chain.hedera.HederaAdapter
import com.altaran.watchwallet.chain.kaspa.KaspaAdapter
import com.altaran.watchwallet.chain.litecoin.LitecoinAdapter
import com.altaran.watchwallet.chain.near.NearAdapter
import com.altaran.watchwallet.chain.polkadot.PolkadotAdapter
import com.altaran.watchwallet.chain.solana.SolanaAdapter
import com.altaran.watchwallet.chain.stellar.StellarAdapter
import com.altaran.watchwallet.chain.sui.SuiAdapter
import com.altaran.watchwallet.chain.tezos.TezosAdapter
import com.altaran.watchwallet.chain.ton.TonAdapter
import com.altaran.watchwallet.chain.tron.TronAdapter
import com.altaran.watchwallet.chain.xrp.XrpAdapter
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
import com.altaran.watchwallet.core.datastore.SettingsDataSource
import com.altaran.watchwallet.core.datastore.dataStoreModule
import com.altaran.watchwallet.core.model.NetworkSettings
import com.altaran.watchwallet.core.network.ProxyConfig
import com.altaran.watchwallet.core.network.createHttpClient
import com.altaran.watchwallet.core.pricing.BinanceMarketData
import com.altaran.watchwallet.core.pricing.CoinGeckoProvider
import com.altaran.watchwallet.core.pricing.ExchangePrices
import com.altaran.watchwallet.core.pricing.FxRates
import com.altaran.watchwallet.core.pricing.KrakenMarketData
import com.altaran.watchwallet.core.pricing.LayeredPriceProvider
import com.altaran.watchwallet.core.pricing.PriceProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module
import org.koin.dsl.module

val dataModule: Module = module {
    includes(dataStoreModule, databaseModule)

    // Network settings are read once at startup; changing them asks the user to restart the app.
    single<NetworkSettings> { runBlocking { get<SettingsDataSource>().settings.first().network } }
    single<HttpClient> {
        val network = get<NetworkSettings>()
        createHttpClient(proxy = if (network.proxyEnabled) ProxyConfig(network.proxyHost, network.proxyPort) else null)
    }
    single<List<ChainAdapter>> {
        val network = get<NetworkSettings>()
        listOf<ChainAdapter>(
            // A custom server is used alone; the defaults fall back to each other.
            BitcoinAdapter(get(), network.esploraUrl?.let(::listOf) ?: BitcoinChain.DEFAULT_ESPLORA_URLS),
            EvmAdapter(get()),
            LitecoinAdapter(get()),
            DogecoinAdapter(get()),
            BitcoinCashAdapter(get()),
            SolanaAdapter(get()),
            XrpAdapter(get()),
            TronAdapter(get()),
            TonAdapter(get()),
            CardanoAdapter(get()),
            CosmosAdapter(get()),
            PolkadotAdapter(get()),
            StellarAdapter(get()),
            SuiAdapter(get()),
            AptosAdapter(get()),
            NearAdapter(get()),
            HederaAdapter(get()),
            AlgorandAdapter(get()),
            TezosAdapter(get()),
            KaspaAdapter(get()),
        )
    }
    single { ChainRegistry(get()) }
    single<PriceProvider> {
        val key = get<NetworkSettings>().coinGeckoApiKey
        // Exchange data first (fast, generous limits), CoinGecko for the long tail.
        LayeredPriceProvider(CoinGeckoProvider(get(), apiKey = { key }), ExchangePrices(get()), get())
    }
    single { KrakenMarketData(get()) }
    single { FxRates(get()) }
    single { BinanceMarketData(get()) }
    single { MarketDataRepository(get(), get(), get()) }

    single<SettingsRepository> { DefaultSettingsRepository(get()) }
    single<WalletRepository> { DefaultWalletRepository(get()) }
    single { PriceRepository(get(), get(), get(), get()) }
    single { com.altaran.watchwallet.core.data.repository.PriceDetailRepository(get(), get()) }
    single { PortfolioRepository(get(), get(), get()) }
    single { com.altaran.watchwallet.core.data.repository.CostBasisRepository(get(), get()) }
    single { SyncEngine(get(), get(), get()) }
    single { AddWalletService(get(), get(), get()) }
    single { DemoWallets(get(), get()) }
}
