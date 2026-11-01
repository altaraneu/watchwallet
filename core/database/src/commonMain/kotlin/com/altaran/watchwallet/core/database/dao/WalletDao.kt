package com.altaran.watchwallet.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.database.entity.WalletGroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet WHERE archived = 0 ORDER BY sort_order, id")
    fun observeActiveWallets(): Flow<List<WalletEntity>>

    @Query("SELECT COUNT(*) FROM wallet WHERE archived = 0")
    fun observeActiveWalletCount(): Flow<Int>

    @Insert
    suspend fun insert(wallet: WalletEntity): Long

    @Update
    suspend fun update(wallet: WalletEntity)

    @Query("DELETE FROM wallet WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM wallet_group ORDER BY sort_order, id")
    fun observeGroups(): Flow<List<WalletGroupEntity>>

    @Insert
    suspend fun insertGroup(group: WalletGroupEntity): Long
}
