package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.database.dao.PriceDao
import com.altaran.watchwallet.core.database.dao.WalletDao
import com.altaran.watchwallet.core.database.entity.PriceCurrentEntity
import com.altaran.watchwallet.core.database.entity.PricePointEntity
import com.altaran.watchwallet.core.database.entity.WatchlistEntity
import com.altaran.watchwallet.core.datastore.SettingsDataSource
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.pricing.CurrentPrice
import com.altaran.watchwallet.core.pricing.MarketInfo
import com.altaran.watchwallet.core.pricing.PriceProvider
import com.altaran.watchwallet.core.timeline.TimedPrice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class WatchlistEntry(val priceId: String, val symbol: String, val name: String)

/**
 * Current and historical prices, cached in Room. History is fetched once and then extended
 * incrementally; the network is only hit when the cache is older than the refresh interval.
 */
class PriceRepository(
    private val provider: PriceProvider,
    private val priceDao: PriceDao,
    private val walletDao: WalletDao,
    private val settings: SettingsDataSource,
) {
    private val historyMutex = Mutex()
    private val historyRequests = MutableStateFlow(0)

    /** Chart history is being downloaded; charts show a progress hint meanwhile. */
    val historyLoading: Flow<Boolean> = historyRequests.map { it > 0 }.distinctUntilChanged()
    private val lastHistoryFetch = mutableMapOf<String, Instant>()

    /**
     * `priceId|currency` keys for which the source has no older daily prices (for example an asset
     * listed after the first transaction); not asked again in this session. Only accessed while
     * [historyMutex] is held.
     */
    private val noOlderHistory = HashSet<String>()

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

    /** Refreshes current prices of all tracked assets in the selected currency. */
    suspend fun refreshCurrentPrices(): Result<Unit> = runCatching {
        val currency = settings.settings.first().quoteCurrency.code.lowercase()
        val ids = trackedPriceIds()
        if (ids.isEmpty()) return@runCatching
        val now = Clock.System.now().toEpochMilliseconds()
        val prices = provider.current(ids, currency)
        priceDao.upsertCurrent(prices.values.map { PriceCurrentEntity(it.priceId, currency, it.price, it.change24hPercent, now) })
    }

    /**
     * Makes sure price history covering [range] exists for [priceIds]. Daily history (one year)
     * is fetched at most every 12 hours; hourly history (7 days) at most every 30 minutes.
     */
    suspend fun ensureHistory(priceIds: Collection<String>, currency: QuoteCurrency, range: ChartRange) {
        // Charts can be panned back past the selected range: short ranges need daily history too.
        ensureHistory(priceIds, currency, range.needsHourlyPrices)
        if (range.needsHourlyPrices) ensureHistory(priceIds, currency, hourly = false)
    }

    private suspend fun ensureHistory(priceIds: Collection<String>, currency: QuoteCurrency, hourly: Boolean) = historyMutex.withLock {
        val code = currency.code.lowercase()
        val (days, maxAge) = if (hourly) HOURLY_DAYS to 30.minutes else null to 12.hours
        priceIds.forEach { priceId ->
            val key = "$priceId|$code|$hourly"
            if (isFresh(key, maxAge)) return@forEach
            historyRequests.update { it + 1 }
            try {
                val points = provider.history(priceId, code, days)
                priceDao.insertPoints(points.map { PricePointEntity(priceId, code, it.time.toEpochMilliseconds(), it.price) })
                lastHistoryFetch[key] = Clock.System.now()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                // Missing history only leaves a gap in the chart; it is retried on the next request.
            } finally {
                historyRequests.update { it - 1 }
            }
        }
        // Then older history, after every asset has its recent prices (checks the DB first, so it
        // only goes to the network when something is missing).
        if (!hourly) {
            priceIds.forEach { priceId ->
                try {
                    extendBack(priceId, code)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
                ) {
                    // Older prices are optional; the next chart request tries again.
                }
            }
        }
    }

    /**
     * Extends stored daily history back to the oldest transaction of an asset with this price id,
     * one page at a time. Past prices never change, so pages already stored are never fetched
     * again; when the source has nothing older, the price id is not asked again this session.
     * Called with [historyMutex] held.
     */
    private suspend fun extendBack(priceId: String, code: String) {
        val key = "$priceId|$code"
        if (key in noOlderHistory) return
        val needed = walletDao.earliestTransactionTimeForPrice(priceId) ?: return
        repeat(MAX_BACKFILL_PAGES) {
            val earliest = priceDao.earliestPointTime(priceId, code) ?: return
            if (earliest <= needed + BACKFILL_SLACK_MS) return
            // The loading hint is shown only while really waiting for the network.
            historyRequests.update { it + 1 }
            val older = try {
                provider.historyBefore(priceId, code, Instant.fromEpochMilliseconds(earliest))
            } finally {
                historyRequests.update { it - 1 }
            }
            // Nothing older, or nothing before what is stored (no progress): stop asking.
            if (older.none { it.time.toEpochMilliseconds() < earliest }) {
                noOlderHistory += key
                return
            }
            priceDao.insertPoints(older.map { PricePointEntity(priceId, code, it.time.toEpochMilliseconds(), it.price) })
        }
    }

    suspend fun markets(currency: QuoteCurrency, coinIds: Collection<String>): Result<List<MarketInfo>> =
        runCatching { provider.markets(coinIds, currency.code.lowercase()) }

    suspend fun search(query: String): Result<List<com.altaran.watchwallet.core.pricing.CoinSearchResult>> =
        runCatching { provider.search(query) }

    private fun isFresh(key: String, maxAge: Duration): Boolean =
        lastHistoryFetch[key]?.let { Clock.System.now() - it < maxAge } == true

    companion object {
        private const val HOURLY_DAYS = 7

        /** Each page is about 2.7 years of daily prices. */
        private const val MAX_BACKFILL_PAGES = 4
        private const val BACKFILL_SLACK_MS = 2 * 24 * 3600 * 1000L

        /** Shown in Markets even before the user holds anything. */
        val DEFAULT_MARKETS = listOf("bitcoin", "ethereum")
    }
}
