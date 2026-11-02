package com.altaran.watchwallet.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.data.repository.PortfolioRepository
import com.altaran.watchwallet.core.data.repository.PortfolioSnapshot
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.repository.historyPriceIds
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.designsystem.chart.ChartPoint
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.QuoteCurrency
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
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

sealed interface PortfolioUiState {
    data object Loading : PortfolioUiState

    data class Ready(
        val quoteCurrency: QuoteCurrency,
        val discreetMode: Boolean,
        val walletCount: Int,
        val snapshot: PortfolioSnapshot,
        val chart: List<ChartPoint>?,
        val range: ChartRange,
        val refreshing: Boolean,
        val groups: List<com.altaran.watchwallet.core.model.WalletGroup> = emptyList(),
        /** `null` = all wallets. */
        val selectedGroupId: Long? = null,
    ) : PortfolioUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioViewModel(
    private val settingsRepository: SettingsRepository,
    private val walletRepository: WalletRepository,
    private val portfolioRepository: PortfolioRepository,
    private val syncEngine: SyncEngine,
) : ViewModel() {
    private val range = MutableStateFlow(ChartRange.M1)
    private val selectedGroup = MutableStateFlow<Long?>(null)
    private val currency = settingsRepository.settings.map { it.quoteCurrency }.distinctUntilChanged()

    /** Wallet ids of the selected group, or `null` for all wallets (one person's portfolio vs everything). */
    private val walletScope = combine(walletRepository.activeWallets, selectedGroup) { wallets, group ->
        group?.let { id -> wallets.filter { it.groupId == id }.map { it.id }.toSet() }
    }.distinctUntilChanged()

    private val snapshot = combine(currency, walletScope) { c, w -> c to w }.flatMapLatest { (c, w) -> portfolioRepository.snapshot(c, w) }

    private val chart = combine(currency, range, walletScope) { c, r, w -> Triple(c, r, w) }.flatMapLatest { (c, r, w) ->
        portfolioRepository.valueSeries(c, r, w)
            .map<List<com.altaran.watchwallet.core.timeline.TimedValue>, List<ChartPoint>?> { series ->
                series.map { ChartPoint(it.time.toEpochMilliseconds(), it.value, it.flow) }
            }
            .onStart { emit(null) }
    }

    val uiState: StateFlow<PortfolioUiState> = combine(
        settingsRepository.settings,
        walletRepository.activeWalletCount,
        snapshot,
        combine(chart, range, walletRepository.groups, selectedGroup) { c, r, g, sg -> ChartState(c, r, g, sg) },
        syncEngine.state,
    ) { settings, walletCount, snap, chartState, sync ->
        val (points, selected) = chartState.points to chartState.range
        PortfolioUiState.Ready(
            quoteCurrency = settings.quoteCurrency,
            discreetMode = settings.discreetMode,
            walletCount = walletCount,
            snapshot = snap,
            chart = points,
            range = selected,
            refreshing = sync.running && sync.manual,
            groups = chartState.groups,
            selectedGroupId = chartState.selectedGroup,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUiState.Loading)

    init {
        // Fetch price history needed for the visible range (cached; throttled by the repository).
        viewModelScope.launch {
            combine(currency, range, snapshot) { c, r, snap -> Triple(c, r, snap.holdings.historyPriceIds()) }
                .distinctUntilChanged()
                .collectLatest { (c, r, ids) -> portfolioRepository.ensureHistory(c, r, ids) }
        }
    }

    fun onToggleDiscreetMode(current: Boolean) {
        viewModelScope.launch { settingsRepository.setDiscreetMode(!current) }
    }

    fun selectGroup(id: Long?) {
        selectedGroup.value = id
    }

    private data class ChartState(
        val points: List<ChartPoint>?,
        val range: ChartRange,
        val groups: List<com.altaran.watchwallet.core.model.WalletGroup>,
        val selectedGroup: Long?,
    )

    fun setRange(value: ChartRange) {
        range.value = value
    }

    fun refresh() {
        syncEngine.syncAll(manual = true)
    }

    /** Shows or hides assets regardless of the automatic rules (spam, no price). */
    fun setVisibility(assets: List<com.altaran.watchwallet.core.model.Asset>, visible: Boolean) {
        viewModelScope.launch { assets.forEach { walletRepository.setAssetVisibility(it, visible) } }
    }
}
