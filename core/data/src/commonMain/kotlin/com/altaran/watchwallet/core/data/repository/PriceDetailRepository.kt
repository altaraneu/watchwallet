package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.database.dao.PriceDetailDao
import com.altaran.watchwallet.core.database.entity.PriceDetailEntity
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.pricing.PriceProvider
import com.altaran.watchwallet.core.timeline.ChartDetail
import com.altaran.watchwallet.core.timeline.ChartDetailPlanner
import com.altaran.watchwallet.core.timeline.PriceResolution
import com.altaran.watchwallet.core.timeline.TimedPrice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Refined prices of a chart window, by price id. */
data class DetailPrices(
    val detail: ChartDetail,
    val minute: Map<String, List<TimedPrice>> = emptyMap(),
    val hour: Map<String, List<TimedPrice>> = emptyMap(),
) {
    /** [coarse] prices with the hourly and then the minute ones laid over them. */
    fun over(coarse: Map<String, List<TimedPrice>>): Map<String, List<TimedPrice>> {
        if (minute.isEmpty() && hour.isEmpty()) return coarse
        val result = HashMap(coarse)
        (hour.keys + minute.keys).forEach { id ->
            val withHours = ChartDetailPlanner.overlay(coarse[id].orEmpty(), hour[id].orEmpty())
            result[id] = ChartDetailPlanner.overlay(withHours, minute[id].orEmpty())
        }
        return result
    }
}

/**
 * On-demand minute and hourly prices for the part of a chart on screen, cached in their own table.
 *
 * Fetching needs no lock shared with the background price backfill, so the visible window never
 * waits for it, and it uses fewer parallel requests than the exchange client allows, so current
 * prices always find a free slot. Cancelling the caller cancels the requests in flight.
 *
 * Only already fetched spans are skipped; past prices never change. Assets the exchange does not
 * list return nothing at once and simply keep their coarser curve.
 */
class PriceDetailRepository(
    private val provider: PriceProvider,
    private val dao: PriceDetailDao,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val attemptsLock = Mutex()
    private val attempts = HashMap<String, Long>()
    private var lastPrune = 0L

    fun observe(priceIds: List<String>, currency: QuoteCurrency, detail: ChartDetail): Flow<DetailPrices> {
        // A minute window falls back to hourly prices of the same time where minutes are missing.
        val minStep = if (detail.resolution == PriceResolution.MINUTE) MINUTE_SECONDS else HOUR_SECONDS
        val to = if (detail.live) Long.MAX_VALUE else detail.to
        return dao.observe(priceIds, currency.code.lowercase(), minStep, HOUR_SECONDS, detail.from, to).map { rows ->
            val (minute, hour) = rows.partition { it.stepSeconds == MINUTE_SECONDS }
            DetailPrices(detail, minute.toPrices(), hour.toPrices())
        }
    }

    private fun List<PriceDetailEntity>.toPrices() = groupBy({ it.priceId }) { TimedPrice(Instant.fromEpochMilliseconds(it.time), it.price) }

    /** Loads the window now and, while it follows the present, the candles that close later. */
    suspend fun keepLoaded(currency: QuoteCurrency, detail: ChartDetail, priceIds: suspend () -> List<String>) {
        if (detail.resolution == PriceResolution.DAY) return
        while (true) {
            load(priceIds(), currency, detail)
            if (!detail.live) return
            delay(LIVE_REFRESH_MS)
        }
    }

    /** Fetches what the window lacks for [priceIds], most important first. */
    suspend fun load(priceIds: List<String>, currency: QuoteCurrency, detail: ChartDetail) {
        val code = currency.code.lowercase()
        val step = detail.resolution.stepMillis
        val seconds = (step / MILLIS).toInt()
        val to = if (detail.live) now() else detail.to
        val permits = Semaphore(MAX_PARALLEL)
        coroutineScope {
            priceIds.map { id ->
                async { permits.withPermit { fetchMissing(id, code, seconds, step, detail.from, to) } }
            }.awaitAll()
        }
        prune(detail)
    }

    private suspend fun fetchMissing(priceId: String, code: String, seconds: Int, step: Long, from: Long, to: Long) {
        val times = dao.times(priceId, code, seconds, from, to)
        val (start, end) = ChartDetailPlanner.missingSpan(times, from, to, step) ?: return
        // The end is part of the key: the newest candle may be late, and is simply asked for again
        // when the next one closes; a hole in the past is not.
        val key = "$priceId|$code|$seconds|$start|${end / step}"
        val time = now()
        val mayTry = attemptsLock.withLock {
            val last = attempts[key]
            if (last != null && time - last < RETRY_MS) {
                false
            } else {
                if (attempts.size > MAX_ATTEMPTS) attempts.clear()
                attempts[key] = time
                true
            }
        }
        if (!mayTry) return
        try {
            val points = provider.candleCloses(
                priceId,
                code,
                seconds / SECONDS_PER_MINUTE,
                Instant.fromEpochMilliseconds(start),
                Instant.fromEpochMilliseconds(end),
            )
            if (points.isNotEmpty()) dao.insert(points.map { PriceDetailEntity(priceId, code, seconds, it.time.toEpochMilliseconds(), it.price) })
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            // The chart keeps its coarser curve; the attempt above delays the next try.
        }
    }

    /**
     * Keeps minutes for [MINUTE_RETENTION_MS] and hours for [HOUR_RETENTION_MS] of time, plus the
     * window on screen (it may be older), and never more than [MAX_ROWS] rows in total.
     */
    private suspend fun prune(detail: ChartDetail) {
        val time = now()
        val minuteCutoff = time - MINUTE_RETENTION_MS
        val hourCutoff = time - HOUR_RETENTION_MS
        val leftBehind = detail.from < minuteCutoff
        if (!leftBehind && time - lastPrune < PRUNE_INTERVAL_MS) return
        lastPrune = time
        val keepTo = if (detail.live) time else detail.to
        dao.prune(MINUTE_SECONDS, minuteCutoff, detail.from, keepTo)
        dao.prune(HOUR_SECONDS, hourCutoff, detail.from, keepTo)
        dao.trim(MAX_ROWS)
    }

    companion object {
        /** Exchange client allows 4 at once: one stays free for current prices. */
        private const val MAX_PARALLEL = 3
        private const val MILLIS = 1000L
        private const val SECONDS_PER_MINUTE = 60
        private const val MINUTE_SECONDS = 60
        private const val HOUR_SECONDS = 3600
        private const val MAX_ATTEMPTS = 2000

        /** A candle closes every minute: a live window asks for it about then. */
        private const val LIVE_REFRESH_MS = 60_000L
        private val RETRY_MS = 10.minutes.inWholeMilliseconds
        private val PRUNE_INTERVAL_MS = 10.minutes.inWholeMilliseconds
        val MINUTE_RETENTION_MS = 7.days.inWholeMilliseconds
        val HOUR_RETENTION_MS = 90.days.inWholeMilliseconds

        /** About 15 assets with a full week of minutes; roughly 20 MB at most. */
        const val MAX_ROWS = 150_000
    }
}
