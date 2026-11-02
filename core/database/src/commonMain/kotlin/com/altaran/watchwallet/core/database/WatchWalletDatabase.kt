package com.altaran.watchwallet.core.database

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.altaran.watchwallet.core.database.dao.PriceDao
import com.altaran.watchwallet.core.database.dao.WalletDao
import com.altaran.watchwallet.core.database.entity.AssetEntity
import com.altaran.watchwallet.core.database.entity.DerivedAddressEntity
import com.altaran.watchwallet.core.database.entity.PriceCurrentEntity
import com.altaran.watchwallet.core.database.entity.PricePointEntity
import com.altaran.watchwallet.core.database.entity.TxCostBasisEntity
import com.altaran.watchwallet.core.database.entity.WalletBalanceEntity
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.database.entity.WalletGroupEntity
import com.altaran.watchwallet.core.database.entity.WalletTxEntity
import com.altaran.watchwallet.core.database.entity.WatchlistEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Schema history (every version is committed in `schemas/`):
 * - 1: wallet, wallet_group
 * - 2: watch target columns on wallet; asset, wallet_balance, wallet_tx, derived_address,
 *      price_current, price_point, watchlist (additive auto-migration, no data loss)
 * - 3: tx_cost_basis (additive auto-migration, no data loss)
 * - 4: wallet.subtract_from_wallet_id (additive auto-migration, no data loss)
 */
@Database(
    entities = [
        WalletEntity::class,
        WalletGroupEntity::class,
        AssetEntity::class,
        WalletBalanceEntity::class,
        WalletTxEntity::class,
        DerivedAddressEntity::class,
        PriceCurrentEntity::class,
        PricePointEntity::class,
        WatchlistEntity::class,
        TxCostBasisEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
@ConstructedBy(WatchWalletDatabaseConstructor::class)
abstract class WatchWalletDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao

    abstract fun priceDao(): PriceDao

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
