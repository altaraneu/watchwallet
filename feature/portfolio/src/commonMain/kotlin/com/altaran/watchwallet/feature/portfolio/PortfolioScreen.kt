package com.altaran.watchwallet.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altaran.watchwallet.core.designsystem.component.AmountText
import com.altaran.watchwallet.core.designsystem.component.EmptyState
import com.altaran.watchwallet.core.designsystem.component.SkeletonBlock
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.ui.format.MoneyFormatter
import com.altaran.watchwallet.feature.portfolio.generated.resources.Res
import com.altaran.watchwallet.feature.portfolio.generated.resources.portfolio_discreet_off
import com.altaran.watchwallet.feature.portfolio.generated.resources.portfolio_discreet_on
import com.altaran.watchwallet.feature.portfolio.generated.resources.portfolio_empty_action
import com.altaran.watchwallet.feature.portfolio.generated.resources.portfolio_empty_message
import com.altaran.watchwallet.feature.portfolio.generated.resources.portfolio_empty_title
import com.altaran.watchwallet.feature.portfolio.generated.resources.portfolio_title
import com.altaran.watchwallet.feature.portfolio.generated.resources.portfolio_total_value
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun PortfolioRoute(
    onAddWallet: () -> Unit,
    viewModel: PortfolioViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PortfolioScreen(
        uiState = uiState,
        onToggleDiscreetMode = viewModel::onToggleDiscreetMode,
        onAddWallet = onAddWallet,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortfolioScreen(
    uiState: PortfolioUiState,
    onToggleDiscreetMode: (current: Boolean) -> Unit,
    onAddWallet: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.portfolio_title)) },
                actions = {
                    if (uiState is PortfolioUiState.Ready) {
                        DiscreetModeButton(
                            discreet = uiState.discreetMode,
                            onClick = { onToggleDiscreetMode(uiState.discreetMode) },
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            when (uiState) {
                PortfolioUiState.Loading -> {
                    TotalValueSkeleton()
                }

                is PortfolioUiState.Ready -> {
                    TotalValueHeader(
                        formattedValue = MoneyFormatter.format(uiState.totalValue, uiState.quoteCurrency),
                    )
                    if (uiState.walletCount == 0) {
                        EmptyState(
                            icon = WwIcon.Wallets,
                            title = stringResource(Res.string.portfolio_empty_title),
                            message = stringResource(Res.string.portfolio_empty_message),
                            actionLabel = stringResource(Res.string.portfolio_empty_action),
                            onAction = onAddWallet,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscreetModeButton(discreet: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = (if (discreet) WwIcon.VisibilityOff else WwIcon.Visibility).painter(),
            contentDescription = stringResource(
                if (discreet) Res.string.portfolio_discreet_off else Res.string.portfolio_discreet_on,
            ),
        )
    }
}

@Composable
private fun TotalValueHeader(formattedValue: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.portfolio_total_value),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AmountText(
            formattedAmount = formattedValue,
            style = MaterialTheme.typography.displaySmall,
        )
    }
}

@Composable
private fun TotalValueSkeleton() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkeletonBlock(width = 96.dp, height = 16.dp)
        SkeletonBlock(width = 220.dp, height = 40.dp)
    }
}
