package com.altaran.watchwallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.data.platform.AppInfo
import com.altaran.watchwallet.core.data.platform.AppLanguageController
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.model.ThemeMode
import com.altaran.watchwallet.core.model.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SettingsUiState {
    data object Loading : SettingsUiState

    data class Ready(
        val settings: UserSettings,
        /** `null` = follow the system language. */
        val selectedLanguageTag: String?,
        val supportedLanguageTags: List<String>,
        val appInfo: AppInfo,
    ) : SettingsUiState
}

internal class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val languageController: AppLanguageController,
    appInfo: AppInfo,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        languageController.selectedLanguageTag,
    ) { settings, languageTag ->
        SettingsUiState.Ready(
            settings = settings,
            selectedLanguageTag = languageTag,
            supportedLanguageTags = languageController.supportedLanguageTags,
            appInfo = appInfo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState.Loading)

    fun setQuoteCurrency(currency: QuoteCurrency) {
        viewModelScope.launch { settingsRepository.setQuoteCurrency(currency) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setUseDynamicColor(enabled) }
    }

    fun setDiscreetMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDiscreetMode(enabled) }
    }

    fun setLanguage(tag: String?) = languageController.setLanguage(tag)
}
