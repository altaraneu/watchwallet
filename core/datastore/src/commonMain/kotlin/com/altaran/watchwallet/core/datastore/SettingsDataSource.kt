package com.altaran.watchwallet.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.altaran.watchwallet.core.model.NetworkSettings
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.model.ThemeMode
import com.altaran.watchwallet.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists [UserSettings] in a Preferences DataStore.
 *
 * @param defaultQuoteCurrency used until the user picks a currency; normally derived from the
 * device region.
 */
class SettingsDataSource(
    private val dataStore: DataStore<Preferences>,
    private val defaultQuoteCurrency: () -> QuoteCurrency,
) {
    val settings: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            quoteCurrency = QuoteCurrency.fromCode(prefs[Keys.QUOTE_CURRENCY]) ?: defaultQuoteCurrency(),
            themeMode = prefs[Keys.THEME_MODE]
                ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                ?: ThemeMode.SYSTEM,
            useDynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            discreetMode = prefs[Keys.DISCREET_MODE] ?: false,
            appLock = prefs[Keys.APP_LOCK] ?: false,
            secureScreen = prefs[Keys.SECURE_SCREEN] ?: false,
            remoteIcons = prefs[Keys.REMOTE_ICONS] ?: true,
            backgroundRefreshHours = prefs[Keys.BACKGROUND_HOURS] ?: 0,
            backgroundRefreshWifiOnly = prefs[Keys.BACKGROUND_WIFI_ONLY] ?: true,
            network = NetworkSettings(
                proxyEnabled = prefs[Keys.PROXY_ENABLED] ?: false,
                proxyHost = prefs[Keys.PROXY_HOST] ?: NetworkSettings().proxyHost,
                proxyPort = prefs[Keys.PROXY_PORT] ?: NetworkSettings().proxyPort,
                esploraUrl = prefs[Keys.ESPLORA_URL]?.takeIf { it.isNotBlank() },
                coinGeckoApiKey = prefs[Keys.COINGECKO_KEY]?.takeIf { it.isNotBlank() },
                autoUpdateEndpoints = prefs[Keys.ENDPOINTS_AUTO_UPDATE] ?: true,
            ),
        )
    }

    /**
     * Persists values derived from the device on first launch, so they never change silently
     * later (for example when the user switches the app language or travels abroad).
     */
    suspend fun initializeDefaults() {
        dataStore.edit { prefs ->
            if (prefs[Keys.QUOTE_CURRENCY] == null) {
                prefs[Keys.QUOTE_CURRENCY] = defaultQuoteCurrency().code
            }
        }
    }

    suspend fun setQuoteCurrency(currency: QuoteCurrency) = edit { it[Keys.QUOTE_CURRENCY] = currency.code }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }

    suspend fun setUseDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setDiscreetMode(enabled: Boolean) = edit { it[Keys.DISCREET_MODE] = enabled }

    suspend fun setAppLock(enabled: Boolean) = edit { it[Keys.APP_LOCK] = enabled }

    suspend fun setSecureScreen(enabled: Boolean) = edit { it[Keys.SECURE_SCREEN] = enabled }

    suspend fun setRemoteIcons(enabled: Boolean) = edit { it[Keys.REMOTE_ICONS] = enabled }

    suspend fun setBackgroundRefresh(hours: Int, wifiOnly: Boolean) = edit {
        it[Keys.BACKGROUND_HOURS] = hours
        it[Keys.BACKGROUND_WIFI_ONLY] = wifiOnly
    }

    suspend fun setNetwork(network: NetworkSettings) = edit {
        it[Keys.PROXY_ENABLED] = network.proxyEnabled
        it[Keys.PROXY_HOST] = network.proxyHost
        it[Keys.PROXY_PORT] = network.proxyPort
        it[Keys.ESPLORA_URL] = network.esploraUrl.orEmpty()
        it[Keys.COINGECKO_KEY] = network.coinGeckoApiKey.orEmpty()
        it[Keys.ENDPOINTS_AUTO_UPDATE] = network.autoUpdateEndpoints
    }

    /** The verified signed server list, exactly as downloaded (see `:core:endpoints`). */
    val endpointConfig: Flow<String?> = dataStore.data.map { it[Keys.ENDPOINTS_CONFIG] }

    suspend fun setEndpointConfig(envelope: String) = edit { it[Keys.ENDPOINTS_CONFIG] = envelope }

    /** When the server list was last requested, in epoch seconds. */
    val endpointConfigLastCheck: Flow<Long?> = dataStore.data.map { it[Keys.ENDPOINTS_LAST_CHECK] }

    suspend fun setEndpointConfigLastCheck(seconds: Long) = edit { it[Keys.ENDPOINTS_LAST_CHECK] = seconds }

    /** Counts app launches (local only) and returns the new count. */
    suspend fun recordLaunch(): Int {
        var count = 0
        dataStore.edit { prefs ->
            count = (prefs[Keys.LAUNCH_COUNT] ?: 0) + 1
            prefs[Keys.LAUNCH_COUNT] = count
        }
        return count
    }

    val donateReminderDisabled: Flow<Boolean> = dataStore.data.map { it[Keys.DONATE_REMINDER_OFF] ?: false }

    suspend fun disableDonateReminder() = edit { it[Keys.DONATE_REMINDER_OFF] = true }

    val ratePromptDisabled: Flow<Boolean> = dataStore.data.map { it[Keys.RATE_PROMPT_OFF] ?: false }

    suspend fun disableRatePrompt() = edit { it[Keys.RATE_PROMPT_OFF] = true }

    /** The launch count at which to ask for a rating, `null` before it has been scheduled. */
    val nextRatePrompt: Flow<Int?> = dataStore.data.map { it[Keys.RATE_PROMPT_NEXT] }

    suspend fun setNextRatePrompt(launch: Int) = edit { it[Keys.RATE_PROMPT_NEXT] = launch }

    /** Opaque sync bookkeeping of a wallet, `null` before its first sync. Local only, no user data. */
    suspend fun walletSyncState(walletId: Long): String? = dataStore.data.first()[syncStateKey(walletId)]

    suspend fun setWalletSyncState(walletId: Long, state: String) = edit { it[syncStateKey(walletId)] = state }

    /** Forgets the bookkeeping of wallets that no longer exist. */
    suspend fun retainWalletSyncStates(walletIds: Set<Long>) = edit { prefs ->
        val keep = walletIds.map { syncStateKey(it).name }.toSet()
        prefs.asMap().keys.filter { it.name.startsWith(SYNC_STATE_PREFIX) && it.name !in keep }.forEach { prefs.remove(it) }
    }

    private fun syncStateKey(walletId: Long) = stringPreferencesKey("$SYNC_STATE_PREFIX$walletId")

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    private companion object {
        const val SYNC_STATE_PREFIX = "wallet_sync_state_"
    }

    private object Keys {
        val QUOTE_CURRENCY = stringPreferencesKey("quote_currency")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DISCREET_MODE = booleanPreferencesKey("discreet_mode")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val SECURE_SCREEN = booleanPreferencesKey("secure_screen")
        val REMOTE_ICONS = booleanPreferencesKey("remote_icons")
        val BACKGROUND_HOURS = intPreferencesKey("background_refresh_hours")
        val BACKGROUND_WIFI_ONLY = booleanPreferencesKey("background_refresh_wifi_only")
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val ESPLORA_URL = stringPreferencesKey("esplora_url")
        val COINGECKO_KEY = stringPreferencesKey("coingecko_api_key")
        val ENDPOINTS_AUTO_UPDATE = booleanPreferencesKey("endpoints_auto_update")
        val ENDPOINTS_CONFIG = stringPreferencesKey("endpoints_config")
        val ENDPOINTS_LAST_CHECK = longPreferencesKey("endpoints_last_check")
        val LAUNCH_COUNT = intPreferencesKey("launch_count")
        val RATE_PROMPT_OFF = booleanPreferencesKey("rate_prompt_off")
        val RATE_PROMPT_NEXT = intPreferencesKey("rate_prompt_next")
        val DONATE_REMINDER_OFF = booleanPreferencesKey("donate_reminder_off")
    }
}
