package com.altaran.watchwallet.core.data

import com.altaran.watchwallet.chain.algorand.AlgorandAdapter
import com.altaran.watchwallet.chain.algorand.AlgorandChain
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.aptos.AptosAdapter
import com.altaran.watchwallet.chain.aptos.AptosChain
import com.altaran.watchwallet.chain.bitcoin.BitcoinAdapter
import com.altaran.watchwallet.chain.bitcoincash.BitcoinCashAdapter
import com.altaran.watchwallet.chain.bitcoincash.BitcoinCashChain
import com.altaran.watchwallet.chain.cardano.CardanoAdapter
import com.altaran.watchwallet.chain.cardano.CardanoChain
import com.altaran.watchwallet.chain.cosmos.CosmosAdapter
import com.altaran.watchwallet.chain.dogecoin.DogecoinAdapter
import com.altaran.watchwallet.chain.dogecoin.DogecoinChain
import com.altaran.watchwallet.chain.evm.EvmAdapter
import com.altaran.watchwallet.chain.evm.EvmNetworks
import com.altaran.watchwallet.chain.hedera.HederaAdapter
import com.altaran.watchwallet.chain.hedera.HederaChain
import com.altaran.watchwallet.chain.kaspa.KaspaAdapter
import com.altaran.watchwallet.chain.kaspa.KaspaChain
import com.altaran.watchwallet.chain.litecoin.LitecoinAdapter
import com.altaran.watchwallet.chain.litecoin.LitecoinChain
import com.altaran.watchwallet.chain.near.NearAdapter
import com.altaran.watchwallet.chain.near.NearChain
import com.altaran.watchwallet.chain.polkadot.PolkadotAdapter
import com.altaran.watchwallet.chain.polkadot.PolkadotChains
import com.altaran.watchwallet.chain.solana.SolanaAdapter
import com.altaran.watchwallet.chain.solana.SolanaChain
import com.altaran.watchwallet.chain.stellar.StellarAdapter
import com.altaran.watchwallet.chain.stellar.StellarChain
import com.altaran.watchwallet.chain.sui.SuiAdapter
import com.altaran.watchwallet.chain.sui.SuiChain
import com.altaran.watchwallet.chain.tezos.TezosAdapter
import com.altaran.watchwallet.chain.tezos.TezosChain
import com.altaran.watchwallet.chain.ton.TonAdapter
import com.altaran.watchwallet.chain.ton.TonChain
import com.altaran.watchwallet.chain.tron.TronAdapter
import com.altaran.watchwallet.chain.tron.TronChain
import com.altaran.watchwallet.chain.xrp.XrpAdapter
import com.altaran.watchwallet.chain.xrp.XrpChain
import com.altaran.watchwallet.core.data.chain.ChainRegistry
import com.altaran.watchwallet.core.data.platform.AppInfo
import com.altaran.watchwallet.core.data.platform.SyncLog
import com.altaran.watchwallet.core.data.repository.DefaultSettingsRepository
import com.altaran.watchwallet.core.data.repository.DefaultWalletRepository
import com.altaran.watchwallet.core.data.repository.MarketDataRepository
import com.altaran.watchwallet.core.data.repository.PortfolioRepository
import com.altaran.watchwallet.core.data.repository.PriceRepository
import com.altaran.watchwallet.core.data.repository.SettingsEndpointConfigStore
import com.altaran.watchwallet.core.data.repository.SettingsPriceSourceStore
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.sync.DataStoreWalletSyncStateStore
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.data.sync.WalletSyncStateStore
import com.altaran.watchwallet.core.database.databaseModule
import com.altaran.watchwallet.core.datastore.SettingsDataSource
import com.altaran.watchwallet.core.datastore.dataStoreModule
import com.altaran.watchwallet.core.endpoints.BundledEndpoints
import com.altaran.watchwallet.core.endpoints.EndpointCatalog
import com.altaran.watchwallet.core.endpoints.EndpointConfigUpdater
import com.altaran.watchwallet.core.endpoints.EndpointProtocol
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.NetworkSettings
import com.altaran.watchwallet.core.network.ProxyConfig
import com.altaran.watchwallet.core.network.createHttpClient
import com.altaran.watchwallet.core.pricing.BinanceMarketData
import com.altaran.watchwallet.core.pricing.CoinGeckoProvider
import com.altaran.watchwallet.core.pricing.CoinGeckoUsage
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
import kotlin.time.Clock

val dataModule: Module = module {
    includes(dataStoreModule, databaseModule)

    // Network settings are read once at startup; changing them asks the user to restart the app.
    single<NetworkSettings> { runBlocking { get<SettingsDataSource>().settings.first().network } }
    single<HttpClient> {
        val network = get<NetworkSettings>()
        createHttpClient(proxy = if (network.proxyEnabled) ProxyConfig(network.proxyHost, network.proxyPort) else null)
    }
    // Read once at startup too: a newly downloaded server list applies at the next app start.
    single<EndpointCatalog> {
        val network = get<NetworkSettings>()
        val stored = runBlocking { get<SettingsDataSource>().endpointConfig.first() }
        // A stored server that the rules no longer allow (plain http to a public host) is not used.
        val customServer = network.esploraUrl?.let(com.altaran.watchwallet.core.common.CustomServerUrl::validate)
        EndpointCatalog.resolve(stored, network.autoUpdateEndpoints, customServer, Clock.System.now().epochSeconds)
    }
    single {
        val debug = get<AppInfo>().isDebug
        EndpointConfigUpdater(
            http = get(),
            store = SettingsEndpointConfigStore(get()),
            debugLog = { message -> if (debug) println("WatchWallet: $message") },
        )
    }
    single<List<ChainAdapter>> {
        val endpoints = get<EndpointCatalog>()
        listOf<ChainAdapter>(
            // A custom server is used alone (see EndpointCatalog); the defaults fall back to each other.
            BitcoinAdapter(get(), endpoints.urls(ChainId.BITCOIN, EndpointProtocol.ESPLORA)),
            EvmAdapter(get(), EvmNetworks.withEndpoints(endpoints)),
            LitecoinAdapter(get(), endpoints.first(LitecoinChain.ID, EndpointProtocol.ESPLORA)),
            DogecoinAdapter(get(), endpoints.first(DogecoinChain.ID, EndpointProtocol.BLOCKBOOK)),
            BitcoinCashAdapter(get(), endpoints.first(BitcoinCashChain.ID, EndpointProtocol.BLOCKBOOK)),
            SolanaAdapter(get(), endpoints.urls(SolanaChain.ID, EndpointProtocol.SOLANA_RPC)),
            XrpAdapter(get(), endpoints.urls(XrpChain.ID, EndpointProtocol.XRPL)),
            TronAdapter(get(), endpoints.first(TronChain.ID, EndpointProtocol.TRONGRID)),
            TonAdapter(get(), endpoints.first(TonChain.ID, EndpointProtocol.TONCENTER)),
            CardanoAdapter(get(), endpoints.first(CardanoChain.ID, EndpointProtocol.KOIOS)),
            CosmosAdapter(get(), endpoints.first(BundledEndpoints.COSMOS, EndpointProtocol.COSMOS_REST)),
            PolkadotAdapter(get(), PolkadotChains.withEndpoints(endpoints)),
            StellarAdapter(get(), endpoints.first(StellarChain.ID, EndpointProtocol.HORIZON)),
            SuiAdapter(get(), endpoints.first(SuiChain.ID, EndpointProtocol.SUI_GRAPHQL)),
            AptosAdapter(get(), endpoints.first(AptosChain.ID, EndpointProtocol.APTOS_REST)),
            NearAdapter(get(), endpoints.urls(NearChain.ID, EndpointProtocol.NEAR_RPC)),
            HederaAdapter(get(), endpoints.first(HederaChain.ID, EndpointProtocol.HEDERA_MIRROR)),
            AlgorandAdapter(get(), endpoints.first(AlgorandChain.ID, EndpointProtocol.ALGORAND_INDEXER)),
            TezosAdapter(get(), endpoints.first(TezosChain.ID, EndpointProtocol.TZKT)),
            KaspaAdapter(get(), endpoints.first(KaspaChain.ID, EndpointProtocol.KASPA_REST)),
        )
    }
    single { ChainRegistry(get()) }
    single { CoinGeckoUsage(SettingsPriceSourceStore(get())) }
    single<PriceProvider> {
        val key = get<NetworkSettings>().coinGeckoApiKey
        // Exchange data first (fast, generous limits), CoinGecko for the long tail.
        LayeredPriceProvider(CoinGeckoProvider(get(), apiKey = { key }), ExchangePrices(get()), get(), get())
    }
    single { KrakenMarketData(get()) }
    single { FxRates(get()) }
    single { BinanceMarketData(get()) }
    single { MarketDataRepository(get(), get(), get()) }

    single<SettingsRepository> { DefaultSettingsRepository(get()) }
    single<WalletRepository> { DefaultWalletRepository(get()) }
    single { PriceRepository(get(), get(), get(), get(), coinGecko = get()) }
    single { com.altaran.watchwallet.core.data.repository.PriceDetailRepository(get(), get()) }
    single { PortfolioRepository(get(), get(), get()) }
    single { com.altaran.watchwallet.core.data.repository.CostBasisRepository(get(), get()) }
    single<WalletSyncStateStore> { DataStoreWalletSyncStateStore(get()) }
    single { SyncEngine(get(), get(), get<PriceRepository>(), get(), getOrNull<SyncLog>() ?: SyncLog.None) }
    single { AddWalletService(get(), get(), get()) }
    single<AddWalletOperations> { get<AddWalletService>() }
    single { DemoWallets(get(), get()) }
}
