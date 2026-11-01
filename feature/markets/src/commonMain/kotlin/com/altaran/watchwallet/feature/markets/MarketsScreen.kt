package com.altaran.watchwallet.feature.markets

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.altaran.watchwallet.core.designsystem.component.EmptyState
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.feature.markets.generated.resources.Res
import com.altaran.watchwallet.feature.markets.generated.resources.markets_empty_message
import com.altaran.watchwallet.feature.markets.generated.resources.markets_empty_title
import com.altaran.watchwallet.feature.markets.generated.resources.markets_title
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource

@Serializable
data object MarketsRoute

fun NavGraphBuilder.marketsScreen() {
    composable<MarketsRoute> { MarketsScreen() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarketsScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(Res.string.markets_title)) }) },
    ) { padding ->
        EmptyState(
            icon = WwIcon.Markets,
            title = stringResource(Res.string.markets_empty_title),
            message = stringResource(Res.string.markets_empty_message),
            modifier = Modifier.padding(padding),
        )
    }
}
