package com.altaran.watchwallet.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.altaran.watchwallet.core.database.entity.PriceDetailEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PriceDetailDao {
    /** Rows of the given steps between [from] and [to], in time order. */
    @Query(
        "SELECT * FROM price_detail WHERE currency = :currency AND price_id IN (:priceIds) " +
            "AND step_seconds BETWEEN :minStep AND :maxStep AND time BETWEEN :from AND :to ORDER BY time",
    )
    fun observe(priceIds: List<String>, currency: String, minStep: Int, maxStep: Int, from: Long, to: Long): Flow<List<PriceDetailEntity>>

    @Query(
        "SELECT time FROM price_detail WHERE price_id = :priceId AND currency = :currency AND step_seconds = :step " +
            "AND time BETWEEN :from AND :to ORDER BY time",
    )
    suspend fun times(priceId: String, currency: String, step: Int, from: Long, to: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(points: List<PriceDetailEntity>)

    /** Drops rows of [step] older than [cutoff], except those of the window on screen. */
    @Query("DELETE FROM price_detail WHERE step_seconds = :step AND time < :cutoff AND NOT (time BETWEEN :keepFrom AND :keepTo)")
    suspend fun prune(step: Int, cutoff: Long, keepFrom: Long, keepTo: Long)

    /** Keeps the [maxRows] most recent rows at most. */
    @Query("DELETE FROM price_detail WHERE rowid IN (SELECT rowid FROM price_detail ORDER BY time DESC LIMIT -1 OFFSET :maxRows)")
    suspend fun trim(maxRows: Int)
}
