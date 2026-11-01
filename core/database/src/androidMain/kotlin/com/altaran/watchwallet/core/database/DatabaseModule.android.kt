package com.altaran.watchwallet.core.database

import androidx.room.Room
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module

internal actual fun Module.databaseBuilder(): Module = apply {
    single {
        val context = androidContext()
        Room.databaseBuilder<WatchWalletDatabase>(
            context = context,
            name = context.getDatabasePath(WatchWalletDatabase.FILE_NAME).absolutePath,
        ).buildWatchWalletDatabase()
    }
}
