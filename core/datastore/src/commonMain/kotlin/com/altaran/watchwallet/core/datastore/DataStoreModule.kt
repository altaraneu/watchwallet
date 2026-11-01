package com.altaran.watchwallet.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.altaran.watchwallet.core.model.QuoteCurrency
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module

internal const val SETTINGS_FILE_NAME = "settings.preferences_pb"

/** Registers the platform's settings [DataStore] (file in the app's private storage). */
internal expect fun Module.settingsDataStore(): Module

/**
 * ISO 4217 currency of the device's **region** (not of the app language: a user who picks
 * Czech as the app language may live anywhere). `null` if the region is unknown.
 */
internal expect fun systemRegionCurrencyCode(): String?

fun createSettingsDataStore(absolutePath: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(produceFile = { absolutePath.toPath() })

val dataStoreModule: Module = module {
    settingsDataStore()
    single {
        SettingsDataSource(
            dataStore = get(),
            defaultQuoteCurrency = { QuoteCurrency.forDeviceCurrency(systemRegionCurrencyCode()) },
        )
    }
}
