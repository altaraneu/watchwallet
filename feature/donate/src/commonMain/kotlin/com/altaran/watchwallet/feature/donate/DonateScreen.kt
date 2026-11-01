package com.altaran.watchwallet.feature.donate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.ui.CopyButton
import com.altaran.watchwallet.feature.donate.generated.resources.Res
import com.altaran.watchwallet.feature.donate.generated.resources.donate_btc_label
import com.altaran.watchwallet.feature.donate.generated.resources.donate_copy_description
import com.altaran.watchwallet.feature.donate.generated.resources.donate_eth_label
import com.altaran.watchwallet.feature.donate.generated.resources.donate_intro
import com.altaran.watchwallet.feature.donate.generated.resources.donate_navigate_up
import com.altaran.watchwallet.feature.donate.generated.resources.donate_title
import com.altaran.watchwallet.feature.donate.generated.resources.donate_verify
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource

@Serializable
data object DonateRoute

fun NavGraphBuilder.donateScreen(onNavigateUp: () -> Unit) {
    composable<DonateRoute> { DonateScreen(onNavigateUp = onNavigateUp) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DonateScreen(onNavigateUp: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.donate_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            painter = WwIcon.ArrowBack.painter(),
                            contentDescription = stringResource(Res.string.donate_navigate_up),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.donate_intro),
                style = MaterialTheme.typography.bodyLarge,
            )
            DonationAddressCard(
                label = stringResource(Res.string.donate_btc_label),
                address = DonationAddresses.BTC_DONATION_ADDRESS,
            )
            DonationAddressCard(
                label = stringResource(Res.string.donate_eth_label),
                address = DonationAddresses.ETH_DONATION_ADDRESS,
            )
            Text(
                text = stringResource(Res.string.donate_verify),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DonationAddressCard(label: String, address: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            CopyButton(
                text = address,
                // Donation addresses are public: no need to mark the clip as sensitive.
                sensitive = false,
                // "Copy" alone is ambiguous with two cards; TalkBack reads "Copy Bitcoin (BTC) address".
                contentDescription = stringResource(Res.string.donate_copy_description, label),
            )
        }
    }
}
