package com.altaran.watchwallet.core.database

import androidx.room.Room
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal actual fun Module.databaseBuilder(): Module = apply {
    single {
        val directory = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        Room.databaseBuilder<WatchWalletDatabase>(
            name = requireNotNull(directory?.path) + "/" + WatchWalletDatabase.FILE_NAME,
        ).buildWatchWalletDatabase()
    }
}
