package com.altaran.watchwallet.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.model.QuoteCurrency
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PortfolioUiState {
    data object Loading : PortfolioUiState

    data class Ready(
        val quoteCurrency: QuoteCurrency,
        val discreetMode: Boolean,
        val walletCount: Int,
        /** Total portfolio value in [quoteCurrency]. Prices arrive in milestone 3. */
        val totalValue: Double,
    ) : PortfolioUiState
}

class PortfolioViewModel(
    private val settingsRepository: SettingsRepository,
    walletRepository: WalletRepository,
) : ViewModel() {
    val uiState: StateFlow<PortfolioUiState> = combine(
        settingsRepository.settings,
        walletRepository.activeWalletCount,
    ) { settings, walletCount ->
        PortfolioUiState.Ready(
            quoteCurrency = settings.quoteCurrency,
            discreetMode = settings.discreetMode,
            walletCount = walletCount,
            totalValue = 0.0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUiState.Loading)

    fun onToggleDiscreetMode(current: Boolean) {
        viewModelScope.launch { settingsRepository.setDiscreetMode(!current) }
    }
}
