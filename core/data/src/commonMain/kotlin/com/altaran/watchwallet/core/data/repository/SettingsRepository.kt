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

    /**
     * The switch in Settings: turning the lock off needs the same authentication as opening the app,
     * or whoever finds the phone unlocked could remove it. Returns whether the setting was changed.
     */
    suspend fun changeAppLock(enabled: Boolean, authenticator: com.altaran.watchwallet.core.data.platform.LockAuthenticator): Boolean {
        if (!enabled && !authenticator.authenticate()) return false
        setAppLock(enabled)
        return true
    }
    suspend fun setSecureScreen(enabled: Boolean)
    suspend fun setRemoteIcons(enabled: Boolean)
    suspend fun setBackgroundRefresh(hours: Int, wifiOnly: Boolean)
    suspend fun setNetwork(network: NetworkSettings)

    /** Counts this app launch; returns the total number of launches. */
    suspend fun recordLaunch(): Int

    val donateReminderDisabled: Flow<Boolean>

    suspend fun disableDonateReminder()

    val ratePromptDisabled: Flow<Boolean>

    suspend fun disableRatePrompt()

    val nextRatePrompt: Flow<Int?>

    suspend fun setNextRatePrompt(launch: Int)
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
    override suspend fun setRemoteIcons(enabled: Boolean) = dataSource.setRemoteIcons(enabled)
    override suspend fun setBackgroundRefresh(hours: Int, wifiOnly: Boolean) = dataSource.setBackgroundRefresh(hours, wifiOnly)
    override suspend fun setNetwork(network: NetworkSettings) = dataSource.setNetwork(network)
    override suspend fun recordLaunch(): Int = dataSource.recordLaunch()
    override val donateReminderDisabled: Flow<Boolean> = dataSource.donateReminderDisabled
    override suspend fun disableDonateReminder() = dataSource.disableDonateReminder()
    override val ratePromptDisabled: Flow<Boolean> = dataSource.ratePromptDisabled
    override suspend fun disableRatePrompt() = dataSource.disableRatePrompt()
    override val nextRatePrompt: Flow<Int?> = dataSource.nextRatePrompt
    override suspend fun setNextRatePrompt(launch: Int) = dataSource.setNextRatePrompt(launch)
}
