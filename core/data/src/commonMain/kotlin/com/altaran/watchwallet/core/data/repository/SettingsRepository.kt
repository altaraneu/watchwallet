package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.datastore.SettingsDataSource
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
}
