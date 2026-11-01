package com.altaran.watchwallet.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.altaran.watchwallet.core.database.entity.AssetEntity
import com.altaran.watchwallet.core.database.entity.DerivedAddressEntity
import com.altaran.watchwallet.core.database.entity.WalletBalanceEntity
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.database.entity.WalletGroupEntity
import com.altaran.watchwallet.core.database.entity.WalletTxEntity
import kotlinx.coroutines.flow.Flow

/** Balance row joined with its asset. */
data class BalanceWithAsset(
    val walletId: Long,
    val raw: String,
    val id: String,
    val chainId: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    val priceId: String?,
    val contract: String?,
    val isSpam: Boolean,
    val userVisible: Boolean?,
)

@Dao
abstract class WalletDao {
    @Query("SELECT * FROM wallet WHERE archived = 0 ORDER BY sort_order, id")
    abstract fun observeActiveWallets(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallet WHERE archived = 0 ORDER BY sort_order, id")
    abstract suspend fun activeWallets(): List<WalletEntity>

    @Query("SELECT * FROM wallet WHERE id = :id")
    abstract fun observeWallet(id: Long): Flow<WalletEntity?>

    @Query("SELECT * FROM wallet WHERE id = :id")
    abstract suspend fun wallet(id: Long): WalletEntity?

    @Query("SELECT COUNT(*) FROM wallet WHERE archived = 0")
    abstract fun observeActiveWalletCount(): Flow<Int>

    @Query("SELECT * FROM wallet WHERE family = :family AND target_value = :value LIMIT 1")
    abstract suspend fun findByTarget(family: String, value: String): WalletEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM wallet")
    abstract suspend fun nextSortOrder(): Int

    @Insert
    abstract suspend fun insert(wallet: WalletEntity): Long

    @Update
    abstract suspend fun update(wallet: WalletEntity)

    @Query("UPDATE wallet SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM wallet WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("DELETE FROM wallet_balance")
    protected abstract suspend fun deleteAllBalances()

    @Query("DELETE FROM wallet_tx")
    protected abstract suspend fun deleteAllTransactions()

    @Query("DELETE FROM derived_address")
    protected abstract suspend fun deleteAllDerived()

    @Query("DELETE FROM wallet")
    protected abstract suspend fun deleteAllWalletRows()

    @Query("DELETE FROM wallet_group")
    protected abstract suspend fun deleteAllGroups()

    /** Removes every wallet with its cached chain data, and all groups, in one transaction. */
    @Transaction
    open suspend fun deleteAllWallets() {
        deleteAllBalances()
        deleteAllTransactions()
        deleteAllDerived()
        deleteAllWalletRows()
        deleteAllGroups()
    }

    @Query("SELECT * FROM wallet_group ORDER BY sort_order, id")
    abstract fun observeGroups(): Flow<List<WalletGroupEntity>>

    @Insert
    abstract suspend fun insertGroup(group: WalletGroupEntity): Long

    @Query("SELECT id FROM wallet_group WHERE name = :name LIMIT 1")
    abstract suspend fun findGroup(name: String): Long?

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM wallet_group")
    abstract suspend fun nextGroupSortOrder(): Int

    // ------------------------------------------------------------ balances, history, addresses

    @Query(
        """SELECT b.wallet_id AS walletId, b.raw AS raw, a.id AS id, a.chain_id AS chainId, a.symbol AS symbol,
                  a.name AS name, a.decimals AS decimals, a.price_id AS priceId, a.contract AS contract,
                  a.is_spam AS isSpam, a.user_visible AS userVisible
           FROM wallet_balance b JOIN asset a ON a.id = b.asset_id
           JOIN wallet w ON w.id = b.wallet_id AND w.archived = 0""",
    )
    abstract fun observeAllBalances(): Flow<List<BalanceWithAsset>>

    @Query("SELECT * FROM wallet_tx WHERE wallet_id = :walletId ORDER BY timestamp DESC")
    abstract fun observeTransactions(walletId: Long): Flow<List<WalletTxEntity>>

    @Query(
        """SELECT t.* FROM wallet_tx t JOIN wallet w ON w.id = t.wallet_id AND w.archived = 0
           ORDER BY t.timestamp""",
    )
    abstract fun observeAllTransactions(): Flow<List<WalletTxEntity>>

    @Query("SELECT DISTINCT hash FROM wallet_tx WHERE wallet_id = :walletId AND confirmed = 1")
    abstract suspend fun knownConfirmedHashes(walletId: Long): List<String>

    @Query("SELECT * FROM derived_address WHERE wallet_id = :walletId ORDER BY is_change, derivation_index")
    abstract fun observeDerivedAddresses(walletId: Long): Flow<List<DerivedAddressEntity>>

    @Query("SELECT * FROM asset")
    abstract fun observeAssets(): Flow<List<AssetEntity>>

    @Query("UPDATE asset SET user_visible = :visible WHERE id = :assetId")
    abstract suspend fun setAssetVisibility(assetId: String, visible: Boolean?)

    @Upsert
    protected abstract suspend fun upsertAssets(assets: List<AssetEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAssetsIfMissing(assets: List<AssetEntity>)

    @Query("UPDATE asset SET symbol = :symbol, name = :name, decimals = :decimals, price_id = :priceId, is_spam = :isSpam WHERE id = :id")
    protected abstract suspend fun refreshAssetMetadata(id: String, symbol: String, name: String, decimals: Int, priceId: String?, isSpam: Boolean)

    @Query("DELETE FROM wallet_balance WHERE wallet_id = :walletId")
    protected abstract suspend fun clearBalances(walletId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertBalances(balances: List<WalletBalanceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertTransactions(transactions: List<WalletTxEntity>)

    @Query("DELETE FROM wallet_tx WHERE wallet_id = :walletId AND confirmed = 0")
    protected abstract suspend fun clearPending(walletId: Long)

    @Query("DELETE FROM derived_address WHERE wallet_id = :walletId")
    protected abstract suspend fun clearDerived(walletId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertDerived(addresses: List<DerivedAddressEntity>)

    @Query("UPDATE wallet SET active_chains = :activeChains, last_synced_at = :syncedAt, history_truncated = :truncated WHERE id = :walletId")
    protected abstract suspend fun markSynced(walletId: Long, activeChains: String, syncedAt: Long, truncated: Boolean)

    /**
     * Writes the result of one sync atomically: either the whole snapshot is stored or nothing,
     * so the UI never sees half-updated balances.
     */
    @Transaction
    open suspend fun storeSync(
        walletId: Long,
        assets: List<AssetEntity>,
        balances: List<WalletBalanceEntity>,
        transactions: List<WalletTxEntity>,
        derived: List<DerivedAddressEntity>?,
        activeChains: String,
        syncedAt: Long,
        truncated: Boolean,
    ) {
        // Keep the user's visibility choice when metadata is refreshed.
        insertAssetsIfMissing(assets)
        assets.forEach { refreshAssetMetadata(it.id, it.symbol, it.name, it.decimals, it.priceId, it.isSpam) }
        clearBalances(walletId)
        insertBalances(balances)
        // Pending transactions are re-fetched every sync; drop stale ones (replaced or confirmed).
        clearPending(walletId)
        upsertTransactions(transactions)
        if (derived != null) {
            clearDerived(walletId)
            insertDerived(derived)
        }
        markSynced(walletId, activeChains, syncedAt, truncated)
    }
}
