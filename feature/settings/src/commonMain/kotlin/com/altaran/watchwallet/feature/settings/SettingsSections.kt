package com.altaran.watchwallet.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.altaran.watchwallet.core.designsystem.component.SectionHeader
import com.altaran.watchwallet.core.designsystem.component.SettingsItem
import com.altaran.watchwallet.core.designsystem.component.SettingsSwitchItem
import com.altaran.watchwallet.core.designsystem.component.SingleChoiceDialog
import com.altaran.watchwallet.core.model.NetworkSettings
import com.altaran.watchwallet.feature.settings.generated.resources.*
import com.altaran.watchwallet.feature.settings.generated.resources.Res
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val REFRESH_OPTIONS = listOf(0, 1, 6, 12, 24)

private enum class NetworkDialog { PROXY, ESPLORA, COINGECKO }

/** Security, background refresh and network sections of the settings screen. */
@Composable
internal fun AdvancedSettingsSections(
    state: SettingsUiState.Ready,
    onAppLockChange: (Boolean) -> Unit,
    onSecureScreenChange: (Boolean) -> Unit,
    onBackgroundRefreshChange: (hours: Int, wifiOnly: Boolean) -> Unit,
    onNetworkChange: (NetworkSettings) -> Unit,
    onRestart: () -> Unit,
) {
    val settings = state.settings
    var refreshPicker by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<NetworkDialog?>(null) }

    if (state.appInfo.isAppLockAvailable) {
        SettingsSwitchItem(
            title = stringResource(Res.string.settings_app_lock),
            summary = stringResource(Res.string.settings_app_lock_summary),
            checked = settings.appLock,
            onCheckedChange = onAppLockChange,
        )
    }
    SettingsSwitchItem(
        title = stringResource(Res.string.settings_secure_screen),
        summary = stringResource(Res.string.settings_secure_screen_summary),
        checked = settings.secureScreen,
        onCheckedChange = onSecureScreenChange,
    )

    SectionHeader(stringResource(Res.string.settings_section_refresh))
    SettingsItem(
        title = stringResource(Res.string.settings_background_refresh),
        summary = refreshLabel(settings.backgroundRefreshHours),
        onClick = { refreshPicker = true },
    )
    SettingsSwitchItem(
        title = stringResource(Res.string.settings_wifi_only),
        checked = settings.backgroundRefreshWifiOnly,
        enabled = settings.backgroundRefreshHours > 0,
        onCheckedChange = { onBackgroundRefreshChange(settings.backgroundRefreshHours, it) },
    )

    SectionHeader(stringResource(Res.string.settings_section_network))
    val network = settings.network
    SettingsSwitchItem(
        title = stringResource(Res.string.settings_proxy),
        summary = if (network.proxyEnabled) "${network.proxyHost}:${network.proxyPort}" else stringResource(Res.string.settings_proxy_summary),
        checked = network.proxyEnabled,
        onCheckedChange = { enabled -> onNetworkChange(network.copy(proxyEnabled = enabled)) },
    )
    // The address only matters while the proxy is on.
    if (network.proxyEnabled) {
        SettingsItem(
            title = stringResource(Res.string.settings_proxy_address),
            summary = "${network.proxyHost}:${network.proxyPort}",
            onClick = { dialog = NetworkDialog.PROXY },
        )
    }
    SettingsItem(
        title = stringResource(Res.string.settings_esplora),
        summary = network.esploraUrl ?: stringResource(Res.string.settings_default_server),
        onClick = { dialog = NetworkDialog.ESPLORA },
    )
    SettingsItem(
        title = stringResource(Res.string.settings_coingecko_key),
        summary = if (network.coinGeckoApiKey.isNullOrBlank()) stringResource(Res.string.settings_not_set) else "••••••",
        onClick = { dialog = NetworkDialog.COINGECKO },
    )
    if (state.restartRequired) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.settings_restart_required), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = onRestart) { Text(stringResource(Res.string.settings_restart)) }
            }
        }
    }

    if (refreshPicker) {
        SingleChoiceDialog(
            title = stringResource(Res.string.settings_background_refresh),
            options = REFRESH_OPTIONS,
            selected = settings.backgroundRefreshHours,
            label = { refreshLabel(it) },
            onSelect = {
                refreshPicker = false
                onBackgroundRefreshChange(it, settings.backgroundRefreshWifiOnly)
            },
            onDismiss = { refreshPicker = false },
        )
    }
    when (dialog) {
        NetworkDialog.PROXY -> ProxyDialog(network, onDismiss = { dialog = null }) { host, port ->
            dialog = null
            onNetworkChange(network.copy(proxyHost = host, proxyPort = port))
        }

        NetworkDialog.ESPLORA -> TextDialog(
            title = stringResource(Res.string.settings_esplora),
            hint = stringResource(Res.string.settings_esplora_hint),
            initial = network.esploraUrl.orEmpty(),
            onDismiss = { dialog = null },
        ) { value ->
            dialog = null
            onNetworkChange(network.copy(esploraUrl = value.trim().trimEnd('/').takeIf { it.startsWith("https://") || it.startsWith("http://") }))
        }

        NetworkDialog.COINGECKO -> TextDialog(
            title = stringResource(Res.string.settings_coingecko_key),
            hint = stringResource(Res.string.settings_coingecko_hint),
            initial = network.coinGeckoApiKey.orEmpty(),
            onDismiss = { dialog = null },
        ) { value ->
            dialog = null
            onNetworkChange(network.copy(coinGeckoApiKey = value.trim().ifBlank { null }))
        }

        null -> Unit
    }
}

@Composable
private fun refreshLabel(hours: Int): String =
    if (hours == 0) stringResource(Res.string.settings_refresh_off) else pluralStringResource(Res.plurals.settings_refresh_every_hours, hours, hours)

@Composable
private fun ProxyDialog(network: NetworkSettings, onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var host by remember { mutableStateOf(network.proxyHost) }
    var port by remember { mutableStateOf(network.proxyPort.toString()) }
    val portValue = port.toIntOrNull()?.takeIf { it in 1..65535 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_proxy_address)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.settings_proxy_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = host, onValueChange = {
                    host = it.trim()
                }, label = { Text(stringResource(Res.string.settings_proxy_host)) }, singleLine = true)
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(Res.string.settings_proxy_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = host.isNotBlank() && portValue != null, onClick = {
                onConfirm(host, portValue!!)
            }) { Text(stringResource(Res.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.settings_cancel)) } },
    )
}

@Composable
private fun TextDialog(title: String, hint: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(stringResource(Res.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.settings_cancel)) } },
    )
}
