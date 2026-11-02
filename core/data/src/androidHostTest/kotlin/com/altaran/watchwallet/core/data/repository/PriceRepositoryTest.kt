package com.altaran.watchwallet.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.altaran.watchwallet.core.database.WatchWalletDatabase
import com.altaran.watchwallet.core.database.entity.PricePointEntity
import com.altaran.watchwallet.core.datastore.SettingsDataSource
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.pricing.CoinSearchResult
import com.altaran.watchwallet.core.pricing.CurrentPrice
import com.altaran.watchwallet.core.pricing.MarketInfo
import com.altaran.watchwallet.core.pricing.PriceDataException
import com.altaran.watchwallet.core.pricing.PricePoint
import com.altaran.watchwallet.core.pricing.PriceProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private class FakeProvider : PriceProvider {
    val lock = Mutex()
    var currentCalls = 0
    val historyCalls = mutableListOf<String>()
    val backfillCalls = mutableListOf<String>()
    var currentGate: CompletableDeferred<Unit>? = null
    var historyGate: CompletableDeferred<Unit>? = null
    var backfillGate: CompletableDeferred<Unit>? = null
    var failHistory = false
    var slowBatch: CompletableDeferred<Unit>? = null

    override suspend fun current(priceIds: Collection<String>, currency: String): Map<String, CurrentPrice> = error("batches are used")

    override suspend fun currentInBatches(priceIds: Collection<String>, currency: String, onBatch: suspend (Map<String, CurrentPrice>) -> Unit) {
        lock.withLock { currentCalls++ }
        currentGate?.await()
        onBatch(priceIds.take(1).associateWith { CurrentPrice(it, currency, 1.0, null) })
        slowBatch?.await()
        onBatch(priceIds.drop(1).associateWith { CurrentPrice(it, currency, 2.0, null) })
    }

    override suspend fun history(priceId: String, currency: String, days: Int?): List<PricePoint> {
        lock.withLock { historyCalls += "$priceId:$days" }
        historyGate?.await()
        if (failHistory) throw PriceDataException("down")
        return listOf(PricePoint(Clock.System.now() - 1.days, 1.0))
    }

    override suspend fun historyBefore(priceId: String, currency: String, before: Instant): List<PricePoint> {
        lock.withLock { backfillCalls += priceId }
        backfillGate?.await()
        return emptyList()
    }

    override suspend fun markets(coinIds: Collection<String>, currency: String): List<MarketInfo> = emptyList()

    override suspend fun search(query: String): List<CoinSearchResult> = emptyList()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PriceRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder<WatchWalletDatabase>(context).setDriver(BundledSQLiteDriver()).build()
    private val provider = FakeProvider()
    private var time = Clock.System.now()
    private val storeFile = File.createTempFile("settings", ".preferences_pb", context.cacheDir).also { it.delete() }
    private val settings = SettingsDataSource(PreferenceDataStoreFactory.create { storeFile }) { QuoteCurrency.DEFAULT }
    private val repository = PriceRepository(provider, db.priceDao(), db.walletDao(), settings, now = { time })
    private val code = QuoteCurrency.USD.code.lowercase()

    @After
    fun close() {
        db.close()
        storeFile.delete()
    }

    private suspend fun storedPrices() = repository.currentPrices(settings.settings.first().quoteCurrency).first()

    @Test
    fun concurrentPriceRefreshesShareOneRequest() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        provider.currentGate = gate
        val callers = List(5) { async { repository.refreshCurrentPrices() } }
        delay(200)
        gate.complete(Unit)
        callers.forEach { it.await() }
        assertEquals(1, provider.currentCalls)
    }

    @Test
    fun freshPricesAreNotFetchedAgain() = runBlocking {
        repository.refreshCurrentPrices()
        repository.refreshCurrentPrices()
        assertEquals(1, provider.currentCalls)
        time += 31.seconds
        repository.refreshCurrentPrices()
        assertEquals(2, provider.currentCalls)
    }

    @Test
    fun firstBatchIsStoredBeforeTheSlowOnesFinish() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        provider.slowBatch = slow
        val refresh = async { repository.refreshCurrentPrices() }
        repeat(50) { if (storedPrices().isEmpty()) delay(20) }
        assertEquals("known assets are on screen", 1, storedPrices().size)
        slow.complete(Unit)
        refresh.await()
        assertEquals(2, storedPrices().size)
    }

    @Test
    fun screensAskingForTheSameHistoryShareOneRequest() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        provider.historyGate = gate
        val callers = List(3) { async { repository.ensureHistory(listOf("bitcoin"), QuoteCurrency.USD, ChartRange.Y1) } }
        delay(200)
        gate.complete(Unit)
        callers.forEach { it.await() }
        assertEquals(listOf("bitcoin:null"), provider.historyCalls)
        repository.ensureHistory(listOf("bitcoin"), QuoteCurrency.USD, ChartRange.Y1)
        assertEquals("fresh history is not fetched again", 1, provider.historyCalls.size)
    }

    @Test
    fun oneAssetWaitingForTheNetworkDoesNotBlockAnotherScreen() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        provider.historyGate = gate
        val slow = async { repository.ensureHistory(listOf("slowcoin"), QuoteCurrency.USD, ChartRange.Y1) }
        repeat(200) { if (provider.lock.withLock { provider.historyCalls.isEmpty() }) delay(25) }
        provider.historyGate = null
        repository.ensureHistory(listOf("bitcoin"), QuoteCurrency.USD, ChartRange.Y1)
        assertTrue("bitcoin:null" in provider.historyCalls)
        assertFalse(slow.isCompleted)
        gate.complete(Unit)
        slow.await()
    }

    @Test
    fun historyStoredByAnEarlierRunIsNotFetchedAgain() = runBlocking {
        val day = 24 * 3600 * 1000L
        val now = time.toEpochMilliseconds()
        db.priceDao().insertPoints(
            listOf(PricePointEntity("bitcoin", code, now - 360 * day, 1.0), PricePointEntity("bitcoin", code, now - 2.hours.inWholeMilliseconds, 1.0)),
        )
        repository.ensureHistory(listOf("bitcoin"), QuoteCurrency.USD, ChartRange.Y1)
        assertTrue(provider.historyCalls.isEmpty())
    }

    @Test
    fun failuresWaitLongerEachTime() = runBlocking {
        provider.failHistory = true
        val ensure = suspend { repository.ensureHistory(listOf("bitcoin"), QuoteCurrency.USD, ChartRange.Y1) }
        ensure()
        ensure()
        assertEquals("waits after the failure", 1, provider.historyCalls.size)
        time += 3.minutes
        ensure()
        assertEquals("a short outage is retried soon", 2, provider.historyCalls.size)
        time += 3.minutes
        ensure()
        assertEquals("then less often", 2, provider.historyCalls.size)
    }

    @Test
    fun anUnavailablePriceDoesNotLoseStoredOnes() = runBlocking {
        repository.refreshCurrentPrices()
        val before = storedPrices()
        val failing = object : PriceProvider by provider {
            override suspend fun currentInBatches(priceIds: Collection<String>, currency: String, onBatch: suspend (Map<String, CurrentPrice>) -> Unit) =
                throw PriceDataException("down")
        }
        PriceRepository(failing, db.priceDao(), db.walletDao(), settings).refreshCurrentPrices()
        assertEquals(before, storedPrices())
    }
}
