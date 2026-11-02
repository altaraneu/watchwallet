package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.database.dao.PriceDetailDao
import com.altaran.watchwallet.core.database.entity.PriceDetailEntity
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.pricing.CoinSearchResult
import com.altaran.watchwallet.core.pricing.CurrentPrice
import com.altaran.watchwallet.core.pricing.MarketInfo
import com.altaran.watchwallet.core.pricing.PriceDataException
import com.altaran.watchwallet.core.pricing.PricePoint
import com.altaran.watchwallet.core.pricing.PriceProvider
import com.altaran.watchwallet.core.timeline.ChartDetail
import com.altaran.watchwallet.core.timeline.PriceResolution
import com.altaran.watchwallet.core.timeline.TimedPrice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class PriceDetailRepositoryTest {
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val now = 100 * day
    private val currency = QuoteCurrency.USD

    private class FakeDao : PriceDetailDao {
        val rows = MutableStateFlow<List<PriceDetailEntity>>(emptyList())
        val pruned = mutableListOf<Int>()
        var trims = 0

        override fun observe(priceIds: List<String>, currency: String, minStep: Int, maxStep: Int, from: Long, to: Long): Flow<List<PriceDetailEntity>> =
            rows.map { all ->
                all.filter {
                    it.priceId in priceIds && it.currency == currency && it.stepSeconds in minStep..maxStep && it.time in from..to
                }.sortedBy { it.time }
            }

        override suspend fun times(priceId: String, currency: String, step: Int, from: Long, to: Long): List<Long> =
            rows.value.filter { it.priceId == priceId && it.currency == currency && it.stepSeconds == step && it.time in from..to }.map { it.time }.sorted()

        override suspend fun insert(points: List<PriceDetailEntity>) {
            rows.value = rows.value + points
        }

        override suspend fun prune(step: Int, cutoff: Long, keepFrom: Long, keepTo: Long) {
            pruned += step
            rows.value = rows.value.filterNot { it.stepSeconds == step && it.time < cutoff && it.time !in keepFrom..keepTo }
        }

        override suspend fun trim(maxRows: Int) {
            trims++
        }
    }

    private inner class FakeProvider(private val failing: Set<String> = emptySet(), private val empty: Set<String> = emptySet()) : PriceProvider {
        val requests = mutableListOf<Triple<String, Long, Long>>()

        override suspend fun candleCloses(priceId: String, currency: String, stepMinutes: Int, from: Instant, to: Instant): List<PricePoint> {
            requests += Triple(priceId, from.toEpochMilliseconds(), to.toEpochMilliseconds())
            if (priceId in failing) throw PriceDataException("down")
            if (priceId in empty) return emptyList()
            val step = stepMinutes * minute
            return generateSequence(from.toEpochMilliseconds()) { it + step }.takeWhile { it <= to.toEpochMilliseconds() }
                .map { PricePoint(Instant.fromEpochMilliseconds(it), 1.0) }.toList()
        }

        override suspend fun current(priceIds: Collection<String>, currency: String) = emptyMap<String, CurrentPrice>()

        override suspend fun history(priceId: String, currency: String, days: Int?) = emptyList<PricePoint>()

        override suspend fun markets(coinIds: Collection<String>, currency: String) = emptyList<MarketInfo>()

        override suspend fun search(query: String) = emptyList<CoinSearchResult>()
    }

    private val window = ChartDetail(PriceResolution.MINUTE, now - 10 * minute, now, live = false)

    @Test
    fun aWindowIsOneRequestPerAssetAndNotFetchedTwice() = runTest {
        val dao = FakeDao()
        val provider = FakeProvider()
        val repository = PriceDetailRepository(provider, dao) { now }
        repository.load(listOf("bitcoin", "ethereum"), currency, window)
        assertEquals(setOf("bitcoin", "ethereum"), provider.requests.map { it.first }.toSet())
        assertEquals(2, provider.requests.size)
        repository.load(listOf("bitcoin", "ethereum"), currency, window)
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun onlyTheMissingPartIsRequested() = runTest {
        val dao = FakeDao()
        dao.insert((0L..5L).map { PriceDetailEntity("bitcoin", "usd", 60, now - 10 * minute + it * minute, 1.0) })
        val provider = FakeProvider()
        PriceDetailRepository(provider, dao) { now }.load(listOf("bitcoin"), currency, window)
        assertEquals(listOf(Triple("bitcoin", now - 5 * minute, now)), provider.requests)
    }

    @Test
    fun assetsWithoutDataOrFailingOnesDoNotStopTheOthers() = runTest {
        val dao = FakeDao()
        val provider = FakeProvider(failing = setOf("down"), empty = setOf("unlisted"))
        val repository = PriceDetailRepository(provider, dao) { now }
        repository.load(listOf("down", "unlisted", "bitcoin"), currency, window)
        assertTrue(dao.rows.value.all { it.priceId == "bitcoin" })
        assertEquals(11, dao.rows.value.size)
        // Neither is asked again right away for the same span.
        val before = provider.requests.size
        repository.load(listOf("down", "unlisted"), currency, window)
        assertEquals(before, provider.requests.size)
    }

    @Test
    fun pruningRemovesOldMinutesButNotTheWindowOnScreen() = runTest {
        val dao = FakeDao()
        val old = now - 30 * day
        dao.insert(
            listOf(
                PriceDetailEntity("bitcoin", "usd", 60, old, 1.0),
                PriceDetailEntity("bitcoin", "usd", 60, now - 6 * day, 1.0),
                PriceDetailEntity("bitcoin", "usd", 3600, old, 1.0),
            ),
        )
        val past = ChartDetail(PriceResolution.MINUTE, now - 60 * day, now - 60 * day + 10 * minute, live = false)
        PriceDetailRepository(FakeProvider(), dao) { now }.load(listOf("bitcoin"), currency, past)
        val left = dao.rows.value
        assertTrue(left.none { it.time == old && it.stepSeconds == 60 }, "an old minute was kept")
        assertTrue(left.any { it.time == now - 6 * day }, "a recent minute was dropped")
        assertTrue(left.any { it.time == old && it.stepSeconds == 3600 }, "an hour within its longer limit was dropped")
        assertTrue(left.any { it.time in past.from..past.to }, "the window on screen was dropped")
        assertEquals(1, dao.trims)
    }

    @Test
    fun finePricesAreLaidOverTheCoarseOnesOnlyWhereTheyExist() = runTest {
        val dao = FakeDao()
        val start = window.from
        dao.insert(
            listOf(
                PriceDetailEntity("bitcoin", "usd", 3600, start + minute, 2.0),
                PriceDetailEntity("bitcoin", "usd", 60, start + 2 * minute, 3.0),
            ),
        )
        val prices = PriceDetailRepository(FakeProvider(), dao) { now }.observe(listOf("bitcoin"), currency, window).first()
        val coarse = mapOf(
            "bitcoin" to listOf(TimedPrice(Instant.fromEpochMilliseconds(0), 1.0), TimedPrice(Instant.fromEpochMilliseconds(now + day), 1.0)),
            "other" to listOf(TimedPrice(Instant.fromEpochMilliseconds(0), 7.0)),
        )
        val merged = prices.over(coarse)
        assertEquals(listOf(1.0, 2.0, 3.0, 1.0), merged.getValue("bitcoin").map { it.price })
        assertEquals(coarse["other"], merged["other"])
    }
}
