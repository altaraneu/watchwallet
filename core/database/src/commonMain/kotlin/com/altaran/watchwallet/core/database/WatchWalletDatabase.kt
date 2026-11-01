package com.altaran.watchwallet.core.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.altaran.watchwallet.core.database.dao.WalletDao
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.database.entity.WalletGroupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        WalletEntity::class,
        WalletGroupEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(WatchWalletDatabaseConstructor::class)
abstract class WatchWalletDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao

    companion object {
        const val FILE_NAME = "watchwallet.db"
    }
}

// Room generates the actual implementations for every target.
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object WatchWalletDatabaseConstructor : RoomDatabaseConstructor<WatchWalletDatabase> {
    override fun initialize(): WatchWalletDatabase
}

/**
 * Applies the settings shared by all platforms to a platform-specific builder.
 *
 * Robustness rules (see CLAUDE.md, "Database"):
 * - WAL journaling: readers never block the writer and the file survives crashes mid-write.
 * - Never use destructive migration: wallets, labels and groups are irreplaceable user data.
 *   Every schema change ships an explicit, tested migration.
 */
internal fun RoomDatabase.Builder<WatchWalletDatabase>.buildWatchWalletDatabase(): WatchWalletDatabase =
    setDriver(BundledSQLiteDriver())
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
