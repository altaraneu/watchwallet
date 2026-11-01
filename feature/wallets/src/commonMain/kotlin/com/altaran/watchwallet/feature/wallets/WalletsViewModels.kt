package com.altaran.watchwallet.feature.wallets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.data.chain.ChainRegistry
import com.altaran.watchwallet.core.data.repository.Holding
import com.altaran.watchwallet.core.data.repository.PortfolioRepository
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.repository.historyPriceIds
import com.altaran.watchwallet.core.data.sync.SyncEngine
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
    val failed: Boolean,
)

data class WalletsUiState(
    val loading: Boolean = true,
    val items: List<WalletListItem> = emptyList(),
    val currency: QuoteCurrency = QuoteCurrency.DEFAULT,
    val refreshing: Boolean = false,
    /** Group id → name, for section headers. */
    val groups: Map<Long, String> = emptyMap(),
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
                    WalletListItem(wallet, value, wallet.id in sync.syncingWallets, wallet.id in sync.errors)
                },
                currency = currency,
                refreshing = sync.running && sync.manual,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletsUiState())

    fun refresh() {
        syncEngine.syncAll(manual = true)
    }
}

data class WalletDetailUiState(
    val wallet: Wallet? = null,
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
    val failed: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class WalletDetailViewModel(
    private val walletId: Long,
    private val walletRepository: WalletRepository,
    private val portfolioRepository: PortfolioRepository,
    settingsRepository: SettingsRepository,
    private val syncEngine: SyncEngine,
    private val registry: ChainRegistry,
) : ViewModel() {
    private val range = MutableStateFlow(ChartRange.M1)
    private val currency = settingsRepository.settings.map { it.quoteCurrency }

    private val chart = combine(currency, range) { c, r -> c to r }.flatMapLatest { (c, r) ->
        portfolioRepository.valueSeries(c, r, setOf(walletId))
            .map<List<com.altaran.watchwallet.core.timeline.TimedValue>, List<ChartPoint>?> { series ->
                series.map { ChartPoint(it.time.toEpochMilliseconds(), it.value) }
            }
            .onStart { emit(null) }
    }

    private val snapshot = currency.flatMapLatest { c -> portfolioRepository.snapshot(c, setOf(walletId)).map { c to it } }

    val state: StateFlow<WalletDetailUiState> = combine(
        walletRepository.wallet(walletId),
        snapshot,
        combine(walletRepository.transactions(walletId), walletRepository.derivedAddresses(walletId), walletRepository.assets) { a, b, c -> Triple(a, b, c) },
        combine(chart, range) { c, r -> c to r },
        syncEngine.state,
    ) { wallet, (cur, snap), (txs, derived, assets), (points, selectedRange), sync ->
        WalletDetailUiState(
            wallet = wallet,
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
            failed = walletId in sync.errors,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletDetailUiState())

    init {
        // Price history for the chart is fetched lazily when the range changes.
        viewModelScope.launch {
            combine(currency, range, portfolioRepository.snapshot(QuoteCurrency.DEFAULT, setOf(walletId))) { c, r, snap ->
                Triple(c, r, snap.holdings.historyPriceIds())
            }.distinctUntilChanged().collect { (c, r, ids) -> portfolioRepository.ensureHistory(c, r, ids) }
        }
    }

    fun chain(id: ChainId): Chain? = registry.chain(id)

    fun setRange(value: ChartRange) {
        range.value = value
    }

    fun refresh() {
        syncEngine.syncWallet(walletId)
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { walletRepository.rename(walletId, name) }
    }

    fun delete() {
        viewModelScope.launch { walletRepository.delete(walletId) }
    }
}
