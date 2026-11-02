package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.data.sync.CurrentPriceRefresher
import com.altaran.watchwallet.core.database.dao.PriceDao
import com.altaran.watchwallet.core.database.dao.WalletDao
import com.altaran.watchwallet.core.database.entity.PriceCurrentEntity
import com.altaran.watchwallet.core.database.entity.PricePointEntity
import com.altaran.watchwallet.core.database.entity.WatchlistEntity
import com.altaran.watchwallet.core.datastore.SettingsDataSource
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.network.SingleFlight
import com.altaran.watchwallet.core.pricing.CoinGeckoUsage
import com.altaran.watchwallet.core.pricing.CurrentPrice
import com.altaran.watchwallet.core.pricing.MarketInfo
import com.altaran.watchwallet.core.pricing.PriceProvider
import com.altaran.watchwallet.core.security.SearchQueryGuard
import com.altaran.watchwallet.core.timeline.TimedPrice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class WatchlistEntry(val priceId: String, val symbol: String, val name: String)

/**
 * Current and historical prices, cached in Room, which is what screens show. The network is only
 * hit when the cache is older than the refresh interval, and screens asking for the same data at
 * the same time share one request. Recent history is fetched first and in parallel; extending it
 * back in time runs in the background, one page at a time, and never delays anything else.
 */
class PriceRepository(
    private val provider: PriceProvider,
    private val priceDao: PriceDao,
    private val walletDao: WalletDao,
    private val settings: SettingsDataSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Instant = { Clock.System.now() },
    private val coinGecko: CoinGeckoUsage = CoinGeckoUsage(),
) : CurrentPriceRefresher {
    private val stateLock = Mutex()
    private val historyRequests = MutableStateFlow(0)
    private val currentFlight = SingleFlight<String, Unit>(scope)
    private val historyFlight = SingleFlight<String, Unit>(scope)
    private val backfillFlight = SingleFlight<String, Unit>(scope)
    private val backfillGate = Semaphore(1)

    private class CurrentRefresh(val at: Instant, val ids: Set<String>, val succeeded: Boolean)

    private val lastCurrentRefresh = mutableMapOf<String, CurrentRefresh>()

    /** Assets with stored prices from CoinGecko, which has to be credited where they are displayed. */
    val coinGeckoPriceIds: Flow<Set<String>> get() = coinGecko.priceIds

    /** Chart history is being downloaded; charts show a progress hint meanwhile. */
    val historyLoading: Flow<Boolean> = historyRequests.map { it > 0 }.distinctUntilChanged()
    private val lastHistoryFetch = mutableMapOf<String, Instant>()

    private class FailedFetch(val count: Int, val at: Instant)

    /** Failed requests wait before the next try: an asset without history must not retry forever. */
    private val failedFetches = mutableMapOf<String, FailedFetch>()

    /**
     * `priceId|currency` keys for which the source has no older daily prices (for example an asset
     * listed after the first transaction); not asked again in this session.
     */
    private val noOlderHistory = HashSet<String>()

    /** Stored prices were removed: what this session remembers having fetched is no longer true. */
    suspend fun forgetFetchedHistory() = stateLock.withLock {
        lastHistoryFetch.clear()
        noOlderHistory.clear()
    }

    fun currentPrices(currency: QuoteCurrency): Flow<Map<String, CurrentPrice>> =
        priceDao.observeCurrent(currency.code.lowercase()).map { rows ->
            rows.associate { it.priceId to CurrentPrice(it.priceId, it.currency, it.price, it.change24h) }
        }

    fun history(priceIds: List<String>, currency: QuoteCurrency): Flow<Map<String, List<TimedPrice>>> =
        priceDao.observeHistory(priceIds, currency.code.lowercase()).map { rows ->
            rows.groupBy({ it.priceId }) { TimedPrice(Instant.fromEpochMilliseconds(it.time), it.price) }
        }

    val watchlist: Flow<List<WatchlistEntry>> =
        priceDao.observeWatchlist().map { rows -> rows.map { WatchlistEntry(it.priceId, it.symbol, it.name) } }

    suspend fun addToWatchlist(entry: WatchlistEntry) =
        priceDao.addToWatchlist(WatchlistEntity(entry.priceId, entry.symbol, entry.name, Int.MAX_VALUE))

    suspend fun removeFromWatchlist(entry: WatchlistEntry) =
        priceDao.removeFromWatchlist(WatchlistEntity(entry.priceId, entry.symbol, entry.name, 0))

    /** Price ids of everything held plus the watchlist. */
    suspend fun trackedPriceIds(): Set<String> {
        val held = walletDao.observeAllBalances().first().mapNotNull { it.priceId }
        val watched = priceDao.observeWatchlist().first().map { it.priceId }
        return (held + watched + DEFAULT_MARKETS).toSet()
    }

    /**
     * Refreshes current prices of all tracked assets in the selected currency, unless that just
     * happened. Prices are stored as they arrive. Failures leave the stored prices in place.
     */
    override suspend fun resetHostHealth() = provider.resetHostHealth()

    override suspend fun refreshCurrentPrices() {
        try {
            val currency = settings.settings.first().quoteCurrency.code.lowercase()
            // A caller joining a running refresh may hold assets it does not cover; the second run
            // only goes to the network in that case.
            repeat(2) { currentFlight.run(currency) { refreshCurrentIfStale(currency) } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            // Stored prices stay on screen; the next refresh tries again.
        }
    }

    private suspend fun refreshCurrentIfStale(currency: String) {
        val ids = trackedPriceIds()
        if (ids.isEmpty()) return
        val last = stateLock.withLock { lastCurrentRefresh[currency] }
        if (last != null && ids.all { it in last.ids } && now() - last.at < if (last.succeeded) CURRENT_FRESH else CURRENT_RETRY) return
        var received = false
        try {
            provider.currentInBatches(ids, currency) { prices ->
                val at = now().toEpochMilliseconds()
                if (prices.isNotEmpty()) received = true
                priceDao.upsertCurrent(prices.values.map { PriceCurrentEntity(it.priceId, currency, it.price, it.change24hPercent, at) })
            }
        } finally {
            stateLock.withLock { lastCurrentRefresh[currency] = CurrentRefresh(now(), ids, received) }
        }
    }

    /**
     * Makes sure price history covering [range] exists for [priceIds], largest holdings first. Daily
     * history (one year) is fetched at most every 12 hours; hourly history (7 days) at most every 30
     * minutes. Older daily prices follow in the background; with [awaitBackfill] the caller waits
     * for them too (cost basis needs the price of every old transaction).
     */
    suspend fun ensureHistory(priceIds: Collection<String>, currency: QuoteCurrency, range: ChartRange, awaitBackfill: Boolean = false) {
        val code = currency.code.lowercase()
        // Charts can be panned back past the selected range: short ranges need daily history too.
        if (range.needsHourlyPrices) fetchRecent(priceIds, code, hourly = true, RECENT_PARALLEL)
        fetchRecent(priceIds, code, hourly = false, RECENT_PARALLEL)
        val backfill = startBackfill(priceIds, code)
        if (awaitBackfill) backfill.join()
    }

    /** For assets no longer held: only their daily prices shape the chart. Does not wait, or block others. */
    fun ensureHistoryInBackground(priceIds: Collection<String>, currency: QuoteCurrency) {
        if (priceIds.isEmpty()) return
        val code = currency.code.lowercase()
        scope.launch {
            fetchRecent(priceIds, code, hourly = false, parallel = 1)
            startBackfill(priceIds, code)
        }
    }

    private suspend fun fetchRecent(priceIds: Collection<String>, code: String, hourly: Boolean, parallel: Int) = coroutineScope {
        val gate = Semaphore(parallel)
        priceIds.forEach { priceId ->
            launch { gate.withPermit { historyFlight.run("$priceId|$code|$hourly") { fetchRecentIfStale(priceId, code, hourly) } } }
        }
    }

    private suspend fun fetchRecentIfStale(priceId: String, code: String, hourly: Boolean) {
        val key = "$priceId|$code|$hourly"
        val (days, maxAge) = if (hourly) HOURLY_DAYS to 30.minutes else null to 12.hours
        val fetchedAt = stateLock.withLock { lastHistoryFetch[key] }
        if (fetchedAt != null && now() - fetchedAt < maxAge) return
        if (isWaitingAfterFailure(key) || isStored(priceId, code, hourly)) return
        historyRequests.update { it + 1 }
        try {
            val points = provider.history(priceId, code, days)
            priceDao.insertPoints(points.map { PricePointEntity(priceId, code, it.time.toEpochMilliseconds(), it.price) })
            stateLock.withLock {
                lastHistoryFetch[key] = now()
                failedFetches.remove(key)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            // Missing history only leaves a gap in the chart; it is retried after a while.
            recordFailure(key)
        } finally {
            historyRequests.update { it - 1 }
        }
    }

    /** The database has this history from earlier runs of the app; past prices do not change. */
    private suspend fun isStored(priceId: String, code: String, hourly: Boolean): Boolean {
        val time = now()
        val latest = priceDao.latestPointTime(priceId, code)?.let(Instant::fromEpochMilliseconds) ?: return false
        return if (hourly) {
            time - latest < STORED_HOURLY_AGE &&
                priceDao.pointCountSince(priceId, code, (time - HOURLY_DAYS.days).toEpochMilliseconds()) >= MIN_STORED_HOURLY_POINTS
        } else {
            val earliest = priceDao.earliestPointTime(priceId, code)?.let(Instant::fromEpochMilliseconds) ?: return false
            time - latest < STORED_DAILY_AGE && time - earliest > MIN_STORED_DAILY_SPAN
        }
    }

    private suspend fun isWaitingAfterFailure(key: String): Boolean = stateLock.withLock {
        val failure = failedFetches[key] ?: return@withLock false
        val wait = when (failure.count) {
            1 -> FIRST_RETRY
            2 -> SECOND_RETRY
            else -> LAST_RETRY
        }
        now() - failure.at < wait
    }

    private suspend fun recordFailure(key: String) = stateLock.withLock {
        failedFetches[key] = FailedFetch((failedFetches[key]?.count ?: 0) + 1, now())
    }

    private fun startBackfill(priceIds: Collection<String>, code: String): Job = scope.launch {
        priceIds.forEach { priceId ->
            backfillFlight.run("$priceId|$code") { backfillGate.withPermit { extendBack(priceId, code) } }
        }
    }

    /**
     * Extends stored daily history back to the oldest transaction of an asset with this price id,
     * one page at a time. Past prices never change, so pages already stored are never fetched
     * again; when the source has nothing older, the price id is not asked again this session.
     * Older prices are optional: a failure is retried after a while, and nobody is told.
     */
    private suspend fun extendBack(priceId: String, code: String) {
        val key = "$priceId|$code"
        if (stateLock.withLock { key in noOlderHistory } || isWaitingAfterFailure(key)) return
        val needed = walletDao.earliestTransactionTimeForPrice(priceId) ?: return
        try {
            repeat(MAX_BACKFILL_PAGES) {
                val earliest = priceDao.earliestPointTime(priceId, code) ?: return
                if (earliest <= needed + BACKFILL_SLACK_MS) return
                val older = provider.historyBefore(priceId, code, Instant.fromEpochMilliseconds(earliest))
                // Nothing older, or nothing before what is stored (no progress): stop asking.
                if (older.none { it.time.toEpochMilliseconds() < earliest }) {
                    stateLock.withLock { noOlderHistory += key }
                    return
                }
                priceDao.insertPoints(older.map { PricePointEntity(priceId, code, it.time.toEpochMilliseconds(), it.price) })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            recordFailure(key)
        }
    }

    suspend fun markets(currency: QuoteCurrency, coinIds: Collection<String>): Result<List<MarketInfo>> =
        runCatching { provider.markets(coinIds, currency.code.lowercase()) }

    suspend fun search(query: String): Result<List<com.altaran.watchwallet.core.pricing.CoinSearchResult>> =
        runCatching { if (SearchQueryGuard.isAllowed(query)) provider.search(query) else emptyList() }

    companion object {
        private val CURRENT_FRESH = 30.seconds
        private val CURRENT_RETRY = 10.seconds
        private const val RECENT_PARALLEL = 4
        private val FIRST_RETRY = 2.minutes
        private val SECOND_RETRY = 10.minutes
        private val LAST_RETRY = 30.minutes

        private const val HOURLY_DAYS = 7
        private val STORED_HOURLY_AGE = 90.minutes
        private const val MIN_STORED_HOURLY_POINTS = 134
        private val STORED_DAILY_AGE = 36.hours
        private val MIN_STORED_DAILY_SPAN = 300.days

        /** Each page is about 2.7 years of daily prices. */
        private const val MAX_BACKFILL_PAGES = 4
        private const val BACKFILL_SLACK_MS = 2 * 24 * 3600 * 1000L

        /** Shown in Markets even before the user holds anything. */
        val DEFAULT_MARKETS = listOf("bitcoin", "ethereum")
    }
}
