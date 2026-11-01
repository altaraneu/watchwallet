package com.altaran.watchwallet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "wallet_group")
data class WalletGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

/**
 * A tracked wallet: user data (name, group, notes) plus what is watched (target columns).
 * Never stores key material.
 */
@Entity(
    tableName = "wallet",
    foreignKeys = [
        ForeignKey(
            entity = WalletGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("group_id"), Index("family", "target_value")],
)
data class WalletEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String?,
    @ColumnInfo(name = "group_id") val groupId: Long?,
    val notes: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val archived: Boolean,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    // --- version 2: watch target ---
    /** [com.altaran.watchwallet.core.model.ChainFamily] name. */
    val family: String? = null,
    @ColumnInfo(name = "chain_id") val chainId: String? = null,
    /** `ADDRESS` or `XPUB`. */
    @ColumnInfo(name = "target_kind") val targetKind: String? = null,
    /** The address or the canonical extended public key. */
    @ColumnInfo(name = "target_value") val targetValue: String? = null,
    @ColumnInfo(name = "script_type") val scriptType: String? = null,
    @ColumnInfo(name = "gap_limit") val gapLimit: Int? = null,
    /** Comma-separated chain ids with activity (EVM networks). */
    @ColumnInfo(name = "active_chains", defaultValue = "") val activeChains: String = "",
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
    @ColumnInfo(name = "history_truncated", defaultValue = "0") val historyTruncated: Boolean = false,
)

/** Asset metadata shared by all wallets (coins and tokens). */
@Entity(tableName = "asset", indices = [Index("chain_id")])
data class AssetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "chain_id") val chainId: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    @ColumnInfo(name = "price_id") val priceId: String?,
    val contract: String?,
    @ColumnInfo(name = "is_spam", defaultValue = "0") val isSpam: Boolean = false,
    /** `null` = automatic (hide spam and unpriced tokens), `1` = always show, `0` = always hide. */
    @ColumnInfo(name = "user_visible") val userVisible: Boolean? = null,
)

@Entity(
    tableName = "wallet_balance",
    primaryKeys = ["wallet_id", "asset_id"],
    foreignKeys = [ForeignKey(WalletEntity::class, ["id"], ["wallet_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("asset_id")],
)
data class WalletBalanceEntity(
    @ColumnInfo(name = "wallet_id") val walletId: Long,
    @ColumnInfo(name = "asset_id") val assetId: String,
    /** Amount in the smallest unit, as a decimal string (exact). */
    val raw: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "wallet_tx",
    primaryKeys = ["wallet_id", "hash", "asset_id"],
    foreignKeys = [ForeignKey(WalletEntity::class, ["id"], ["wallet_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("wallet_id", "timestamp"), Index("asset_id")],
)
data class WalletTxEntity(
    @ColumnInfo(name = "wallet_id") val walletId: Long,
    @ColumnInfo(name = "chain_id") val chainId: String,
    val hash: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    /** Epoch milliseconds. */
    val timestamp: Long,
    val delta: String,
    val fee: String?,
    @ColumnInfo(name = "is_self") val isSelf: Boolean,
    val confirmed: Boolean,
)

@Entity(
    tableName = "derived_address",
    primaryKeys = ["wallet_id", "address"],
    foreignKeys = [ForeignKey(WalletEntity::class, ["id"], ["wallet_id"], onDelete = ForeignKey.CASCADE)],
)
data class DerivedAddressEntity(
    @ColumnInfo(name = "wallet_id") val walletId: Long,
    val address: String,
    @ColumnInfo(name = "is_change") val isChange: Boolean,
    @ColumnInfo(name = "derivation_index") val derivationIndex: Int,
    val used: Boolean,
)

@Entity(tableName = "price_current", primaryKeys = ["price_id", "currency"])
data class PriceCurrentEntity(
    @ColumnInfo(name = "price_id") val priceId: String,
    val currency: String,
    val price: Double,
    @ColumnInfo(name = "change_24h") val change24h: Double?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "price_point", primaryKeys = ["price_id", "currency", "time"])
data class PricePointEntity(
    @ColumnInfo(name = "price_id") val priceId: String,
    val currency: String,
    /** Epoch milliseconds. */
    val time: Long,
    val price: Double,
)

/** Coins the user follows in the Markets tab in addition to held assets. */
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey @ColumnInfo(name = "price_id") val priceId: String,
    val symbol: String,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
