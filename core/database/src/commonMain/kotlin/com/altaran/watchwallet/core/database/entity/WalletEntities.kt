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
 * A tracked wallet. Chain-specific data (addresses, xpub settings, balances) lives in
 * separate tables added by the chain milestones.
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
    indices = [Index("group_id")],
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
)
