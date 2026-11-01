package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.datastore.SettingsDataSource
import com.altaran.watchwallet.core.model.NetworkSettings
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.model.ThemeMode
import com.altaran.watchwallet.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<UserSettings>

    /** Stores device-derived defaults on first launch; a no-op afterwards. */
    suspend fun initializeDefaults()

    suspend fun setQuoteCurrency(currency: QuoteCurrency)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setUseDynamicColor(enabled: Boolean)
    suspend fun setDiscreetMode(enabled: Boolean)
    suspend fun setAppLock(enabled: Boolean)
    suspend fun setSecureScreen(enabled: Boolean)
    suspend fun setBackgroundRefresh(hours: Int, wifiOnly: Boolean)
    suspend fun setNetwork(network: NetworkSettings)

    /** Counts this app launch; returns the total number of launches. */
    suspend fun recordLaunch(): Int

    val donateReminderDisabled: Flow<Boolean>

    suspend fun disableDonateReminder()
}

internal class DefaultSettingsRepository(
    private val dataSource: SettingsDataSource,
) : SettingsRepository {
    override val settings: Flow<UserSettings> = dataSource.settings

    override suspend fun initializeDefaults() = dataSource.initializeDefaults()

    override suspend fun setQuoteCurrency(currency: QuoteCurrency) = dataSource.setQuoteCurrency(currency)
    override suspend fun setThemeMode(mode: ThemeMode) = dataSource.setThemeMode(mode)
    override suspend fun setUseDynamicColor(enabled: Boolean) = dataSource.setUseDynamicColor(enabled)
    override suspend fun setDiscreetMode(enabled: Boolean) = dataSource.setDiscreetMode(enabled)
    override suspend fun setAppLock(enabled: Boolean) = dataSource.setAppLock(enabled)
    override suspend fun setSecureScreen(enabled: Boolean) = dataSource.setSecureScreen(enabled)
    override suspend fun setBackgroundRefresh(hours: Int, wifiOnly: Boolean) = dataSource.setBackgroundRefresh(hours, wifiOnly)
    override suspend fun setNetwork(network: NetworkSettings) = dataSource.setNetwork(network)
    override suspend fun recordLaunch(): Int = dataSource.recordLaunch()
    override val donateReminderDisabled: Flow<Boolean> = dataSource.donateReminderDisabled
    override suspend fun disableDonateReminder() = dataSource.disableDonateReminder()
}
