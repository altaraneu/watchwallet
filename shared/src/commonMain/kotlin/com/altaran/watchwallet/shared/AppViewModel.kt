package com.altaran.watchwallet.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.model.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

sealed interface AppUiState {
    data object Loading : AppUiState
    data class Ready(val settings: UserSettings) : AppUiState
}

/** App-wide state needed before the first frame: theme, dynamic color and discreet mode. */
class AppViewModel(settingsRepository: SettingsRepository) : ViewModel() {
    val uiState: StateFlow<AppUiState> = settingsRepository.settings
        .onStart { settingsRepository.initializeDefaults() }
        .map<UserSettings, AppUiState> { AppUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState.Loading)
}
