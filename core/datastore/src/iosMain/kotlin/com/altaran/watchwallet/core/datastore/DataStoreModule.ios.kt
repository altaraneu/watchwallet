package com.altaran.watchwallet.core.datastore

import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDomainMask
import platform.Foundation.currencyCode
import platform.Foundation.currentLocale

@OptIn(ExperimentalForeignApi::class)
internal actual fun Module.settingsDataStore(): Module = apply {
    single {
        val directory = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        createSettingsDataStore(requireNotNull(directory?.path) + "/" + SETTINGS_FILE_NAME)
    }
}

internal actual fun systemRegionCurrencyCode(): String? = NSLocale.currentLocale.currencyCode
