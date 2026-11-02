package com.altaran.watchwallet.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.altaran.watchwallet.core.database.entity.PriceDetailEntity
import com.altaran.watchwallet.core.database.entity.TxCostBasisEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Upgrades a database created exactly like schema v2 (from the committed `schemas/…/2.json`)
 * to the current version and checks that user data survives and new tables work.
 * SDK 34: newer Robolectric SDKs fail to start on this JDK (shared memory interceptor).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val schemaDir = File("schemas/com.altaran.watchwallet.core.database.WatchWalletDatabase")

    private fun createV2(file: File) {
        val schema = Json.parseToJsonElement(File(schemaDir, "2.json").readText()).jsonObject.getValue("database").jsonObject
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        schema.getValue("entities").jsonArray.forEach { entity ->
            val obj = entity.jsonObject
            val table = obj.getValue("tableName").jsonPrimitive.content
            connection.execSQL(obj.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            obj["indices"]?.jsonArray?.forEach { index ->
                connection.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        schema.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
        connection.execSQL("PRAGMA user_version = 2")
        connection.execSQL(
            "INSERT INTO wallet (id, name, sort_order, archived, created_at_epoch_ms, family, chain_id, target_kind, target_value, " +
                "gap_limit, active_chains, history_truncated) " +
                "VALUES (1, 'Savings', 0, 0, 0, 'UTXO', 'bitcoin', 'ADDRESS', '1Archive1n2C579dMsAu3iC6tWzuQJz8dN', 20, 'bitcoin', 0)",
        )
        connection.close()
    }

    @Test
    fun migratesFrom2ToCurrentKeepingWallets() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("migration-test.db").also {
            it.parentFile?.mkdirs()
            it.delete()
        }
        createV2(file)

        val db = Room.databaseBuilder<WatchWalletDatabase>(context, file.absolutePath).buildWatchWalletDatabase()
        val dao = db.walletDao()
        assertEquals(listOf("Savings"), dao.activeWallets().map { it.name })
        assertEquals(null, dao.activeWallets().single().subtractFromWalletId)
        dao.upsertCostBasis(listOf(TxCostBasisEntity(1, "tx1", "bitcoin:native", 1234.5, userSet = true, updatedAt = 0)))
        assertEquals(1234.5, dao.costBasis().single().valueUsd, 0.0)
        // Version 5: the refined price cache, separate from the long price history.
        val detail = db.priceDetailDao()
        detail.insert(
            listOf(
                PriceDetailEntity("bitcoin", "usd", 60, 1_000, 1.0),
                PriceDetailEntity("bitcoin", "usd", 60, 2_000, 2.0),
                PriceDetailEntity("bitcoin", "usd", 60, 3_000, 3.0),
            ),
        )
        assertEquals(listOf(2_000L), detail.times("bitcoin", "usd", 60, 1_500, 2_500))
        detail.prune(60, cutoff = 2_500, keepFrom = 1_000, keepTo = 1_000)
        assertEquals(listOf(1_000L, 3_000L), detail.times("bitcoin", "usd", 60, 0, 5_000))
        detail.trim(maxRows = 1)
        assertEquals(listOf(3_000L), detail.times("bitcoin", "usd", 60, 0, 5_000))
        db.close()
    }
}
