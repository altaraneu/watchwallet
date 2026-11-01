package com.altaran.watchwallet.core.datastore

import android.content.res.Resources
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import java.util.Currency

internal actual fun Module.settingsDataStore(): Module = apply {
    single {
        createSettingsDataStore(androidContext().filesDir.resolve(SETTINGS_FILE_NAME).absolutePath)
    }
}

// System resources are not affected by the per-app language, so the region is the user's real one.
internal actual fun systemRegionCurrencyCode(): String? = runCatching {
    val systemLocale = Resources.getSystem().configuration.locales[0]
    Currency.getInstance(systemLocale).currencyCode
}.getOrNull()
