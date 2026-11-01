package com.altaran.watchwallet.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altaran.watchwallet.core.designsystem.component.SectionHeader
import com.altaran.watchwallet.core.designsystem.component.SettingsItem
import com.altaran.watchwallet.core.designsystem.component.SettingsSwitchItem
import com.altaran.watchwallet.core.designsystem.component.SingleChoiceDialog
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.model.ThemeMode
import com.altaran.watchwallet.core.ui.format.DisplayNames
import com.altaran.watchwallet.feature.settings.generated.resources.Res
import com.altaran.watchwallet.feature.settings.generated.resources.currency_name_btc
import com.altaran.watchwallet.feature.settings.generated.resources.currency_name_eth
import com.altaran.watchwallet.feature.settings.generated.resources.settings_charts_attribution
import com.altaran.watchwallet.feature.settings.generated.resources.settings_charts_attribution_summary
import com.altaran.watchwallet.feature.settings.generated.resources.settings_currency
import com.altaran.watchwallet.feature.settings.generated.resources.settings_export
import com.altaran.watchwallet.feature.settings.generated.resources.settings_export_summary
import com.altaran.watchwallet.feature.settings.generated.resources.settings_import
import com.altaran.watchwallet.feature.settings.generated.resources.settings_import_summary
import com.altaran.watchwallet.feature.settings.generated.resources.settings_section_data
import com.altaran.watchwallet.feature.settings.generated.resources.settings_demo_wallets
import com.altaran.watchwallet.feature.settings.generated.resources.settings_demo_wallets_summary
import com.altaran.watchwallet.feature.settings.generated.resources.settings_section_developer
import com.altaran.watchwallet.feature.settings.generated.resources.settings_currency_label
import com.altaran.watchwallet.feature.settings.generated.resources.settings_discreet_mode
import com.altaran.watchwallet.feature.settings.generated.resources.settings_discreet_mode_summary
import com.altaran.watchwallet.feature.settings.generated.resources.settings_dynamic_color
import com.altaran.watchwallet.feature.settings.generated.resources.settings_dynamic_color_summary
import com.altaran.watchwallet.feature.settings.generated.resources.settings_language
import com.altaran.watchwallet.feature.settings.generated.resources.settings_language_system
import com.altaran.watchwallet.feature.settings.generated.resources.settings_license
import com.altaran.watchwallet.feature.settings.generated.resources.settings_license_summary
import com.altaran.watchwallet.feature.settings.generated.resources.settings_section_about
import com.altaran.watchwallet.feature.settings.generated.resources.settings_section_display
import com.altaran.watchwallet.feature.settings.generated.resources.settings_section_privacy
import com.altaran.watchwallet.feature.settings.generated.resources.settings_source_code
import com.altaran.watchwallet.feature.settings.generated.resources.settings_support
import com.altaran.watchwallet.feature.settings.generated.resources.settings_support_summary
import com.altaran.watchwallet.feature.settings.generated.resources.settings_theme
import com.altaran.watchwallet.feature.settings.generated.resources.settings_theme_dark
import com.altaran.watchwallet.feature.settings.generated.resources.settings_theme_light
import com.altaran.watchwallet.feature.settings.generated.resources.settings_theme_system
import com.altaran.watchwallet.feature.settings.generated.resources.settings_title
import com.altaran.watchwallet.feature.settings.generated.resources.settings_version
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private enum class Picker { CURRENCY, THEME, LANGUAGE }

@Composable
internal fun SettingsRoute(
    onOpenSupport: () -> Unit,
    onOpenImport: () -> Unit = {},
    onOpenExport: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onSelectCurrency = viewModel::setQuoteCurrency,
        onSelectTheme = viewModel::setThemeMode,
        onDynamicColorChange = viewModel::setUseDynamicColor,
        onDiscreetModeChange = viewModel::setDiscreetMode,
        onSelectLanguage = viewModel::setLanguage,
        onOpenSupport = onOpenSupport,
        onOpenImport = onOpenImport,
        onOpenExport = onOpenExport,
        onSeedDemo = viewModel::seedDemoWallets,
        onAppLockChange = viewModel::setAppLock,
        onSecureScreenChange = viewModel::setSecureScreen,
        onBackgroundRefreshChange = viewModel::setBackgroundRefresh,
        onNetworkChange = viewModel::setNetwork,
        onRestart = viewModel::restart,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    uiState: SettingsUiState,
    onSelectCurrency: (QuoteCurrency) -> Unit,
    onSelectTheme: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onDiscreetModeChange: (Boolean) -> Unit,
    onSelectLanguage: (String?) -> Unit,
    onOpenSupport: () -> Unit,
    onOpenImport: () -> Unit = {},
    onOpenExport: () -> Unit = {},
    onSeedDemo: () -> Unit = {},
    onAppLockChange: (Boolean) -> Unit = {},
    onSecureScreenChange: (Boolean) -> Unit = {},
    onBackgroundRefreshChange: (Int, Boolean) -> Unit = { _, _ -> },
    onNetworkChange: (com.altaran.watchwallet.core.model.NetworkSettings) -> Unit = {},
    onRestart: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (uiState !is SettingsUiState.Ready) return@Scaffold
        var openPicker by rememberSaveable { mutableStateOf<Picker?>(null) }
        val settings = uiState.settings
        val uriHandler = LocalUriHandler.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(Res.string.settings_section_display))
            SettingsItem(
                title = stringResource(Res.string.settings_currency),
                summary = currencyLabel(settings.quoteCurrency),
                onClick = { openPicker = Picker.CURRENCY },
            )
            SettingsItem(
                title = stringResource(Res.string.settings_theme),
                summary = themeLabel(settings.themeMode),
                onClick = { openPicker = Picker.THEME },
            )
            if (uiState.appInfo.isDynamicColorAvailable) {
                SettingsSwitchItem(
                    title = stringResource(Res.string.settings_dynamic_color),
                    summary = stringResource(Res.string.settings_dynamic_color_summary),
                    checked = settings.useDynamicColor,
                    onCheckedChange = onDynamicColorChange,
                )
            }
            SettingsItem(
                title = stringResource(Res.string.settings_language),
                summary = languageLabel(uiState.selectedLanguageTag),
                onClick = { openPicker = Picker.LANGUAGE },
            )

            SectionHeader(stringResource(Res.string.settings_section_data))
            SettingsItem(
                title = stringResource(Res.string.settings_import),
                summary = stringResource(Res.string.settings_import_summary),
                showChevron = true,
                onClick = onOpenImport,
            )
            SettingsItem(
                title = stringResource(Res.string.settings_export),
                summary = stringResource(Res.string.settings_export_summary),
                showChevron = true,
                onClick = onOpenExport,
            )

            SectionHeader(stringResource(Res.string.settings_section_privacy))
            SettingsSwitchItem(
                title = stringResource(Res.string.settings_discreet_mode),
                summary = stringResource(Res.string.settings_discreet_mode_summary),
                checked = settings.discreetMode,
                onCheckedChange = onDiscreetModeChange,
            )
            AdvancedSettingsSections(
                state = uiState,
                onAppLockChange = onAppLockChange,
                onSecureScreenChange = onSecureScreenChange,
                onBackgroundRefreshChange = onBackgroundRefreshChange,
                onNetworkChange = onNetworkChange,
                onRestart = onRestart,
            )

            SectionHeader(stringResource(Res.string.settings_section_about))
            SettingsItem(
                title = stringResource(Res.string.settings_support),
                summary = stringResource(Res.string.settings_support_summary),
                showChevron = true,
                onClick = onOpenSupport,
            )
            SettingsItem(
                title = stringResource(Res.string.settings_source_code),
                summary = uiState.appInfo.sourceCodeUrl,
                onClick = { uriHandler.openUri(uiState.appInfo.sourceCodeUrl) },
            )
            SettingsItem(
                title = stringResource(Res.string.settings_charts_attribution),
                summary = stringResource(Res.string.settings_charts_attribution_summary),
                onClick = { uriHandler.openUri("https://www.tradingview.com/") },
            )
            SettingsItem(
                title = stringResource(Res.string.settings_license),
                summary = stringResource(Res.string.settings_license_summary),
            )
            SettingsItem(
                title = stringResource(Res.string.settings_version),
                summary = uiState.appInfo.versionName,
            )
            if (uiState.appInfo.isDebug) {
                SectionHeader(stringResource(Res.string.settings_section_developer))
                SettingsItem(
                    title = stringResource(Res.string.settings_demo_wallets),
                    summary = stringResource(Res.string.settings_demo_wallets_summary),
                    onClick = onSeedDemo,
                )
            }
        }

        when (openPicker) {
            Picker.CURRENCY -> SingleChoiceDialog(
                title = stringResource(Res.string.settings_currency),
                options = QuoteCurrency.entries,
                selected = settings.quoteCurrency,
                label = { currencyLabel(it) },
                onSelect = {
                    onSelectCurrency(it)
                    openPicker = null
                },
                onDismiss = { openPicker = null },
            )

            Picker.THEME -> SingleChoiceDialog(
                title = stringResource(Res.string.settings_theme),
                options = ThemeMode.entries,
                selected = settings.themeMode,
                label = { themeLabel(it) },
                onSelect = {
                    onSelectTheme(it)
                    openPicker = null
                },
                onDismiss = { openPicker = null },
            )

            Picker.LANGUAGE -> SingleChoiceDialog(
                title = stringResource(Res.string.settings_language),
                options = listOf<String?>(null) + uiState.supportedLanguageTags,
                selected = uiState.selectedLanguageTag,
                label = { languageLabel(it) },
                onSelect = {
                    onSelectLanguage(it)
                    openPicker = null
                },
                onDismiss = { openPicker = null },
            )

            null -> Unit
        }
    }
}

@Composable
private fun currencyLabel(currency: QuoteCurrency): String {
    val name = when (currency) {
        QuoteCurrency.BTC -> stringResource(Res.string.currency_name_btc)
        QuoteCurrency.ETH -> stringResource(Res.string.currency_name_eth)
        else -> DisplayNames.currencyName(currency.code) ?: currency.code
    }
    return stringResource(Res.string.settings_currency_label, name, currency.code)
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> Res.string.settings_theme_system
        ThemeMode.LIGHT -> Res.string.settings_theme_light
        ThemeMode.DARK -> Res.string.settings_theme_dark
    },
)

@Composable
private fun languageLabel(tag: String?): String =
    if (tag == null) stringResource(Res.string.settings_language_system) else DisplayNames.languageNativeName(tag)
