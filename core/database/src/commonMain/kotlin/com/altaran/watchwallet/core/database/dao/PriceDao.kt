package com.altaran.watchwallet.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.altaran.watchwallet.core.database.entity.PriceCurrentEntity
import com.altaran.watchwallet.core.database.entity.PricePointEntity
import com.altaran.watchwallet.core.database.entity.WatchlistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PriceDao {
    @Query("SELECT * FROM price_current WHERE currency = :currency")
    fun observeCurrent(currency: String): Flow<List<PriceCurrentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCurrent(prices: List<PriceCurrentEntity>)

    @Query("SELECT * FROM price_point WHERE currency = :currency AND price_id IN (:priceIds) ORDER BY time")
    fun observeHistory(priceIds: List<String>, currency: String): Flow<List<PricePointEntity>>

    @Query("SELECT * FROM price_point WHERE price_id = :priceId AND currency = :currency AND time >= :from ORDER BY time")
    fun observeHistorySince(priceId: String, currency: String, from: Long): Flow<List<PricePointEntity>>

    @Query("SELECT MAX(time) FROM price_point WHERE price_id = :priceId AND currency = :currency")
    suspend fun latestPointTime(priceId: String, currency: String): Long?

    @Query("SELECT MIN(time) FROM price_point WHERE price_id = :priceId AND currency = :currency")
    suspend fun earliestPointTime(priceId: String, currency: String): Long?

    @Query("SELECT COUNT(*) FROM price_point WHERE price_id = :priceId AND currency = :currency AND time >= :from")
    suspend fun pointCountSince(priceId: String, currency: String, from: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<PricePointEntity>)

    @Query("SELECT * FROM watchlist ORDER BY sort_order")
    fun observeWatchlist(): Flow<List<WatchlistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToWatchlist(entry: WatchlistEntity)

    @Delete
    suspend fun removeFromWatchlist(entry: WatchlistEntity)
}
