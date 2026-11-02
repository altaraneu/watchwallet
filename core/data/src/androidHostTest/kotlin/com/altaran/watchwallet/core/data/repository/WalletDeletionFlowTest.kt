package com.altaran.watchwallet.core.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.altaran.watchwallet.core.database.WatchWalletDatabase
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** SDK 34: see `MigrationTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalletDeletionFlowTest {
    private val db = Room.inMemoryDatabaseBuilder<WatchWalletDatabase>(RuntimeEnvironment.getApplication())
        .setDriver(BundledSQLiteDriver())
        .build()
    private var cleanups = 0
    private val repository = DefaultWalletRepository(db.walletDao()) { cleanups++ }
    private val target = WatchTarget.Address(ChainFamily.UTXO, ChainId.BITCOIN, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")

    @After
    fun tearDown() = db.close()

    @Test
    fun deletingAWalletCleansUpAfterwards() = runBlocking {
        val id = repository.addWallet(target, "one", emptyList())
        repository.delete(id)
        assertEquals(1, cleanups)
        assertEquals(0, repository.activeWalletCount.first())
    }

    @Test
    fun deletingEverythingCleansUpAfterwards() = runBlocking {
        repository.addWallet(target, "one", emptyList())
        repository.deleteAll()
        assertEquals(1, cleanups)
    }

    @Test
    fun aFailedCleanupDoesNotUndoOrFailTheDeletion() = runBlocking {
        val failing = DefaultWalletRepository(db.walletDao()) { error("no space left") }
        val id = failing.addWallet(target, "one", emptyList())
        failing.delete(id)
        assertEquals(0, failing.activeWalletCount.first())
    }

    /** The detail screen navigates away right after asking for the deletion. */
    @Test
    fun leavingTheScreenDoesNotStopTheCleanup() = runBlocking {
        var finished = false
        val slow = DefaultWalletRepository(db.walletDao()) {
            delay(50)
            finished = true
        }
        val id = slow.addWallet(target, "one", emptyList())
        lateinit var job: Job
        job = launch { slow.delete(id) }
        withTimeout(5_000) {
            while (slow.activeWalletCount.first() != 0) delay(1)
        }
        job.cancel()
        job.join()
        assertEquals(true, finished)
    }

    @Test
    fun aDecreaseIsReportedAndAnIncreaseIsNot() = runBlocking {
        assertEquals(0, flowOf(0, 1, 2).decreases().toList().size)
        assertEquals(1, flowOf(2, 1).decreases().toList().size)
        assertEquals(2, flowOf(3, 2, 2, 5, 0).decreases().toList().size)
        assertEquals("the first value is no decrease", 0, flowOf(3).decreases().toList().size)
    }
}
