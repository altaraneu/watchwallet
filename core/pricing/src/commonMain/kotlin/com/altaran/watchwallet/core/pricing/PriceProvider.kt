package com.altaran.watchwallet.core.pricing

import kotlin.time.Instant

/** Current price of one asset in one quote currency. */
data class CurrentPrice(
    val priceId: String,
    val currency: String,
    val price: Double,
    /** Percent change over 24 hours, for example `-2.5`. */
    val change24hPercent: Double?,
)

data class PricePoint(val time: Instant, val price: Double)

/** Market overview row for the Markets tab. */
data class MarketInfo(
    val priceId: String,
    val symbol: String,
    val name: String,
    val price: Double,
    val change24hPercent: Double?,
    val change7dPercent: Double?,
    val marketCap: Double?,
    /** Hourly prices over the last 7 days (sparkline). */
    val sparkline7d: List<Double>,
)

/**
 * A source of prices. Price ids are CoinGecko coin ids (`bitcoin`) or contract ids
 * (`token:<platform>:<contract>`). Currency codes are lowercase (`czk`, `usd`, `btc`).
 */
interface PriceProvider {
    suspend fun current(priceIds: Collection<String>, currency: String): Map<String, CurrentPrice>

    /**
     * Like [current], but hands over what is known as soon as it is known: a slow long tail of
     * tokens must not hold back the prices of everything else.
     */
    suspend fun currentInBatches(priceIds: Collection<String>, currency: String, onBatch: suspend (Map<String, CurrentPrice>) -> Unit) {
        onBatch(current(priceIds, currency))
    }

    /**
     * Price history. `days = null` asks for the longest history the provider offers; points are
     * daily for long ranges and hourly for ranges up to 90 days.
     */
    suspend fun history(priceId: String, currency: String, days: Int?): List<PricePoint>

    suspend fun markets(coinIds: Collection<String>, currency: String): List<MarketInfo>

    /**
     * Daily prices older than [before] (one page, oldest first), for extending stored history back
     * in time. Providers without paging return nothing.
     */
    suspend fun historyBefore(priceId: String, currency: String, before: kotlin.time.Instant): List<PricePoint> = emptyList()

    /**
     * Closes of finished candles between [from] and [to], every [stepMinutes] (1 or 60), oldest
     * first, at most 1000. Only sources with cheap bulk access implement this; everything else
     * returns nothing and charts keep the coarser prices they have.
     */
    suspend fun candleCloses(priceId: String, currency: String, stepMinutes: Int, from: kotlin.time.Instant, to: kotlin.time.Instant): List<PricePoint> =
        emptyList()

    /** Coins matching a name or symbol, for adding to the watchlist. */
    suspend fun search(query: String): List<CoinSearchResult>
}

data class CoinSearchResult(val priceId: String, val symbol: String, val name: String, val marketCapRank: Int?)

class PriceDataException(message: String, cause: Throwable? = null) : Exception(message, cause)
