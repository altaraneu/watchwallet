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
        /** Network settings changed since start: a restart is needed to apply them. */
        val restartRequired: Boolean = false,
    ) : SettingsUiState
}

internal class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val languageController: AppLanguageController,
    appInfo: AppInfo,
    private val demoWallets: com.altaran.watchwallet.core.data.DemoWallets,
    /** Network settings the running app was started with. */
    private val activeNetwork: com.altaran.watchwallet.core.model.NetworkSettings,
    private val restarter: com.altaran.watchwallet.core.data.platform.AppRestarter,
    private val walletRepository: com.altaran.watchwallet.core.data.repository.WalletRepository,
    private val lockAuthenticator: com.altaran.watchwallet.core.data.platform.LockAuthenticator,
) : ViewModel() {
    /** Number of tracked wallets, for the "delete all" row. */
    val walletCount: StateFlow<Int> = walletRepository.activeWalletCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun deleteAllWallets() {
        viewModelScope.launch { walletRepository.deleteAll() }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        languageController.selectedLanguageTag,
    ) { settings, languageTag ->
        SettingsUiState.Ready(
            settings = settings,
            selectedLanguageTag = languageTag,
            supportedLanguageTags = languageController.supportedLanguageTags,
            appInfo = appInfo,
            restartRequired = settings.network != activeNetwork,
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

    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.changeAppLock(enabled, lockAuthenticator) }
    }

    fun setRemoteIcons(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setRemoteIcons(enabled) }
    }

    fun setSecureScreen(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSecureScreen(enabled) }
    }

    fun setBackgroundRefresh(hours: Int, wifiOnly: Boolean) {
        viewModelScope.launch { settingsRepository.setBackgroundRefresh(hours, wifiOnly) }
    }

    fun setNetwork(network: com.altaran.watchwallet.core.model.NetworkSettings) {
        viewModelScope.launch { settingsRepository.setNetwork(network) }
    }

    fun restart() = restarter.restart()

    fun seedDemoWallets() {
        viewModelScope.launch { demoWallets.seed() }
    }
}
