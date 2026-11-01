package com.altaran.watchwallet.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.model.ThemeMode
import com.altaran.watchwallet.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists [UserSettings] in a Preferences DataStore.
 *
 * @param defaultQuoteCurrency used until the user picks a currency; normally derived from the
 * device locale.
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

    suspend fun setQuoteCurrency(currency: QuoteCurrency) {
        dataStore.edit { it[Keys.QUOTE_CURRENCY] = currency.code }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setDiscreetMode(enabled: Boolean) {
        dataStore.edit { it[Keys.DISCREET_MODE] = enabled }
    }

    private object Keys {
        val QUOTE_CURRENCY = stringPreferencesKey("quote_currency")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DISCREET_MODE = booleanPreferencesKey("discreet_mode")
    }
}
