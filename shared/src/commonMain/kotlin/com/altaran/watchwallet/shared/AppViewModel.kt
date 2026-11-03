package com.altaran.watchwallet.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.data.platform.AppInfo
import com.altaran.watchwallet.core.data.repository.PriceRepository
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.repository.decreases
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.endpoints.EndpointApprovalRequest
import com.altaran.watchwallet.core.endpoints.EndpointConfigUpdater
import com.altaran.watchwallet.core.endpoints.EndpointListApproval
import com.altaran.watchwallet.core.endpoints.EndpointUpdateResult
import com.altaran.watchwallet.core.model.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

sealed interface AppUiState {
    data object Loading : AppUiState
    data class Ready(val settings: UserSettings) : AppUiState
}

/**
 * App-wide state needed before the first frame (theme, dynamic color, discreet mode), plus the
 * app-start refresh: one sync of all wallets and a price refresh whenever the currency changes.
 */
class AppViewModel(
    private val settingsRepository: SettingsRepository,
    syncEngine: SyncEngine,
    private val priceRepository: PriceRepository,
    private val appInfo: AppInfo,
    private val endpointUpdater: EndpointConfigUpdater,
    private val endpointListApproval: EndpointListApproval,
    walletRepository: WalletRepository,
) : ViewModel() {
    /** A wallet was removed; the screens clear what is cached outside the database. */
    val walletRemoved: Flow<Unit> = walletRepository.activeWalletCount.decreases()

    /** Chart history is downloading (shown as a hint on charts). */
    val priceHistoryLoading: StateFlow<Boolean> =
        priceRepository.historyLoading.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val uiState: StateFlow<AppUiState> = settingsRepository.settings
        .onStart { settingsRepository.initializeDefaults() }
        .map<UserSettings, AppUiState> { AppUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState.Loading)

    private val _showDonateReminder = MutableStateFlow(false)

    /** A gentle donation reminder on the 5th, 10th and 30th launch (unless turned off). */
    val showDonateReminder: StateFlow<Boolean> = _showDonateReminder.asStateFlow()

    fun dismissDonateReminder(forever: Boolean) {
        _showDonateReminder.value = false
        if (forever) viewModelScope.launch { settingsRepository.disableDonateReminder() }
    }

    private val _endpointApproval = MutableStateFlow<EndpointApprovalRequest?>(null)

    /** A signed server list adds servers; it has no effect unless the user approves it. */
    val endpointApproval: StateFlow<EndpointApprovalRequest?> = _endpointApproval.asStateFlow()

    fun answerEndpointApproval(request: EndpointApprovalRequest, approved: Boolean) {
        _endpointApproval.value = null
        viewModelScope.launch { if (approved) endpointListApproval.approve(request) else endpointListApproval.decline(request) }
    }

    /** No answer: nothing changes, and it asks again at the next app start. */
    fun postponeEndpointApproval() {
        _endpointApproval.value = null
    }

    private val _showRatePrompt = MutableStateFlow(false)

    /** Asks for a rating on Google Play every 20 to 30 launches, until the user says they did. */
    val showRatePrompt: StateFlow<Boolean> = _showRatePrompt.asStateFlow()

    fun dismissRatePrompt(alreadyRated: Boolean) {
        _showRatePrompt.value = false
        if (alreadyRated) viewModelScope.launch { settingsRepository.disableRatePrompt() }
    }

    /** One dialog per launch: a rating that is due waits while the donation reminder shows. */
    private suspend fun scheduleRatePrompt(launches: Int) {
        if (!appInfo.canAskForRating || settingsRepository.ratePromptDisabled.first()) return
        val next = settingsRepository.nextRatePrompt.first()
        if (next != null && launches >= next) _showRatePrompt.value = true
        if (next == null || launches >= next) {
            settingsRepository.setNextRatePrompt(launches + Random.nextInt(RATE_PROMPT_MIN_LAUNCHES, RATE_PROMPT_MAX_LAUNCHES + 1))
        }
    }

    init {
        viewModelScope.launch {
            val launches = settingsRepository.recordLaunch()
            if (launches in DONATE_REMINDER_LAUNCHES && !settingsRepository.donateReminderDisabled.first()) {
                _showDonateReminder.value = true
            } else {
                scheduleRatePrompt(launches)
            }
        }
        syncEngine.syncAll()
        // Nothing waits for this; an approved or harmless server list applies at the next app start.
        viewModelScope.launch(Dispatchers.Default) {
            _endpointApproval.value = endpointListApproval.pending()
            if (endpointUpdater.updateIfDue() == EndpointUpdateResult.AWAITING_APPROVAL) _endpointApproval.value = endpointListApproval.pending()
        }
        viewModelScope.launch {
            settingsRepository.settings
                .map { it.quoteCurrency }
                .distinctUntilChanged()
                .drop(1) // the initial sync already refreshes prices
                .collect { priceRepository.refreshCurrentPrices() }
        }
    }

    private var priceRefreshJob: Job? = null

    /**
     * Keeps displayed values fresh while the app is visible: prices refresh every minute only
     * between `onStart` and `onStop`, never in the background.
     */
    fun setForeground(foreground: Boolean) {
        priceRefreshJob?.cancel()
        priceRefreshJob = if (foreground) {
            viewModelScope.launch {
                while (isActive) {
                    delay(PRICE_REFRESH_MS)
                    priceRepository.refreshCurrentPrices()
                }
            }
        } else {
            null
        }
    }

    private companion object {
        const val PRICE_REFRESH_MS = 60_000L
        val DONATE_REMINDER_LAUNCHES = setOf(5, 10, 30)
        const val RATE_PROMPT_MIN_LAUNCHES = 20
        const val RATE_PROMPT_MAX_LAUNCHES = 30
    }
}
