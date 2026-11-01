package com.altaran.watchwallet.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.data.repository.PriceRepository
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.model.UserSettings
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
import kotlinx.coroutines.launch

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
    priceRepository: PriceRepository,
) : ViewModel() {
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

    init {
        viewModelScope.launch {
            val launches = settingsRepository.recordLaunch()
            if (launches in DONATE_REMINDER_LAUNCHES && !settingsRepository.donateReminderDisabled.first()) {
                _showDonateReminder.value = true
            }
        }
        syncEngine.syncAll()
        viewModelScope.launch {
            settingsRepository.settings
                .map { it.quoteCurrency }
                .distinctUntilChanged()
                .drop(1) // the initial sync already refreshes prices
                .collect { priceRepository.refreshCurrentPrices() }
        }
        // Keep displayed values fresh while the app is open.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(PRICE_REFRESH_MS)
                priceRepository.refreshCurrentPrices()
            }
        }
    }

    private companion object {
        const val PRICE_REFRESH_MS = 60_000L
        val DONATE_REMINDER_LAUNCHES = setOf(5, 10, 30)
    }
}
