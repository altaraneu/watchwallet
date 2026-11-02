package com.altaran.watchwallet.feature.wallets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.data.chain.ChainRegistry
import com.altaran.watchwallet.core.data.repository.Holding
import com.altaran.watchwallet.core.data.repository.PortfolioRepository
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.repository.historyPriceIds
import com.altaran.watchwallet.core.data.repository.usesCoinGecko
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.data.sync.WalletSyncHealth
import com.altaran.watchwallet.core.designsystem.chart.ChartPoint
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.DerivedAddress
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.model.Wallet
import com.altaran.watchwallet.core.model.WalletTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WalletListItem(
    val wallet: Wallet,
    val value: Double?,
    val syncing: Boolean,
    val syncHealth: WalletSyncHealth,
)

data class WalletsUiState(
    val loading: Boolean = true,
    val items: List<WalletListItem> = emptyList(),
    val currency: QuoteCurrency = QuoteCurrency.DEFAULT,
    val refreshing: Boolean = false,
    /** Group id → name, for section headers. */
    val groups: Map<Long, String> = emptyMap(),
    /** A value on the list is based on CoinGecko data. */
    val poweredByCoinGecko: Boolean = false,
)

internal class WalletsViewModel(
    walletRepository: WalletRepository,
    portfolioRepository: PortfolioRepository,
    settingsRepository: SettingsRepository,
    private val syncEngine: SyncEngine,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<WalletsUiState> = settingsRepository.settings.map { it.quoteCurrency }.flatMapLatest { currency ->
        combine(walletRepository.activeWallets, portfolioRepository.walletValues(currency), syncEngine.state, walletRepository.groups) {
            wallets,
            values,
            sync,
            groups,
            ->
            WalletsUiState(
                groups = groups.associate { it.id to it.name },
                loading = false,
                items = wallets.map { wallet ->
                    // A synced wallet without balances is empty (0), not unknown.
                    val value = values[wallet.id] ?: 0.0.takeIf { wallet.lastSyncedAt != null }
                    WalletListItem(wallet, value, wallet.id in sync.syncingWallets, sync.healthOf(wallet.id))
                },
                currency = currency,
                refreshing = sync.running && sync.manual,
            )
        }.combine(portfolioRepository.walletsUsingCoinGecko(currency)) { state, ids ->
            state.copy(poweredByCoinGecko = state.items.any { it.value != null && it.wallet.id in ids })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletsUiState())

    fun refresh() {
        syncEngine.syncAll(manual = true)
    }
}

data class WalletDetailUiState(
    val wallet: Wallet? = null,
    /** A new name was refused as key material. */
    val secretRejected: Boolean = false,
    /** The wallet this address is subtracted from, if any. */
    val subtractedFromName: String? = null,
    /** Addresses tracked separately and taken out of this wallet. */
    val separateWalletNames: List<String> = emptyList(),
    val deleted: Boolean = false,
    val currency: QuoteCurrency = QuoteCurrency.DEFAULT,
    val value: Double? = null,
    val holdings: List<Holding> = emptyList(),
    val transactions: List<WalletTransaction> = emptyList(),
    val derivedAddresses: List<DerivedAddress> = emptyList(),
    val assets: Map<AssetId, Asset> = emptyMap(),
    val chart: List<ChartPoint>? = null,
    val range: ChartRange = ChartRange.M1,
    val syncing: Boolean = false,
    val syncHealth: WalletSyncHealth = WalletSyncHealth.OK,
    /** Cost basis (USD) of incoming transfers, by transaction leg. */
    val costs: Map<com.altaran.watchwallet.core.data.repository.CostKey, com.altaran.watchwallet.core.data.repository.CostBasisEntry> = emptyMap(),
    /** An amount on the screen is based on CoinGecko data. */
    val poweredByCoinGecko: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class WalletDetailViewModel(
    private val walletId: Long,
    private val walletRepository: WalletRepository,
    private val portfolioRepository: PortfolioRepository,
    settingsRepository: SettingsRepository,
    private val syncEngine: SyncEngine,
    private val registry: ChainRegistry,
    private val costBasis: com.altaran.watchwallet.core.data.repository.CostBasisRepository,
) : ViewModel() {
    private val range = MutableStateFlow(ChartRange.M1)
    private val secretRejected = MutableStateFlow(false)

    /** Saves what a transfer really cost in USD; `null` returns to the automatic value. */
    fun setCost(tx: com.altaran.watchwallet.core.ui.TxView, valueUsd: Double?) {
        viewModelScope.launch {
            costBasis.setUserValue(com.altaran.watchwallet.core.data.repository.CostKey(tx.walletId, tx.hash, tx.assetId), valueUsd)
        }
    }
    private val currency = settingsRepository.settings.map { it.quoteCurrency }

    /** The part of the chart on screen, refined with better prices. */
    private val viewport = com.altaran.watchwallet.core.timeline.ChartViewport(range.value)

    // A new range keeps the old series until the new one is computed: the chart never goes blank.
    private val chart = currency.flatMapLatest { c ->
        range.flatMapLatest { r -> portfolioRepository.valueSeries(c, r, setOf(walletId), viewport.detail) }
            .map<List<com.altaran.watchwallet.core.timeline.TimedValue>, List<ChartPoint>?> { series ->
                series.map { ChartPoint(it.time.toEpochMilliseconds(), it.value, it.flow) }
            }
            .onStart { emit(null) }
    }

    private val snapshot = currency.flatMapLatest { c -> portfolioRepository.snapshot(c, setOf(walletId)).map { c to it } }

    val state: StateFlow<WalletDetailUiState> = combine(
        combine(walletRepository.wallet(walletId), walletRepository.activeWallets) { wallet, all -> wallet to all },
        snapshot,
        combine(walletRepository.transactions(walletId), walletRepository.derivedAddresses(walletId), walletRepository.assets) { a, b, c -> Triple(a, b, c) },
        combine(chart, range, costBasis.entries) { c, r, costs -> Triple(c, r, costs) },
        syncEngine.state,
    ) { (wallet, all), (cur, snap), (txs, derived, assets), (points, selectedRange, costs), sync ->
        WalletDetailUiState(
            wallet = wallet,
            subtractedFromName = all.firstOrNull { it.id == wallet?.subtractFromWalletId }?.name,
            separateWalletNames = all.filter { it.subtractFromWalletId == walletId }.map { it.name },
            deleted = wallet == null,
            currency = cur,
            value = snap.totalValue,
            holdings = snap.holdings,
            transactions = txs,
            derivedAddresses = derived,
            assets = assets,
            chart = points,
            range = selectedRange,
            syncing = walletId in sync.syncingWallets,
            syncHealth = sync.healthOf(walletId),
            costs = costs,
        )
    }.combine(portfolioRepository.coinGeckoPriceIds) { state, ids ->
        state.copy(poweredByCoinGecko = state.holdings.usesCoinGecko(ids))
    }.combine(secretRejected) { state, rejected -> state.copy(secretRejected = rejected) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletDetailUiState())

    init {
        viewModelScope.launch { costBasis.fillAutomatic() }
        // Price history for the chart is fetched lazily when the range changes.
        viewModelScope.launch {
            combine(currency, range, portfolioRepository.snapshot(QuoteCurrency.DEFAULT, setOf(walletId))) { c, r, snap ->
                Triple(c, r, snap.holdings.historyPriceIds())
            }.distinctUntilChanged().collectLatest { (c, r, ids) -> portfolioRepository.ensureHistory(c, r, ids) }
        }
    }

    fun chain(id: ChainId): Chain? = registry.chain(id)

    fun setRange(value: ChartRange) {
        viewport.show(value)
        range.value = value
    }

    /** The chart settled on a window after pan or zoom (epoch ms). */
    fun onVisibleWindow(from: Long, to: Long) {
        viewport.onVisible(from, to)
    }

    fun refresh() {
        syncEngine.syncWallet(walletId)
    }

    /** Counts this address in the larger wallet again. */
    fun stopSubtracting() {
        viewModelScope.launch { walletRepository.setSubtractFrom(walletId, null) }
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                walletRepository.rename(walletId, name)
            } catch (
                @Suppress("SwallowedException") e: com.altaran.watchwallet.core.security.SecretRejectedException,
            ) {
                secretRejected.value = true
            }
        }
    }

    fun onSecretWarningShown() {
        secretRejected.value = false
    }

    fun delete() {
        viewModelScope.launch { walletRepository.delete(walletId) }
    }
}
