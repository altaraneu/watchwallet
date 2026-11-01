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
     * Price history. `days = null` asks for the longest history the provider offers; points are
     * daily for long ranges and hourly for ranges up to 90 days.
     */
    suspend fun history(priceId: String, currency: String, days: Int?): List<PricePoint>

    suspend fun markets(coinIds: Collection<String>, currency: String): List<MarketInfo>
}

class PriceDataException(message: String, cause: Throwable? = null) : Exception(message, cause)
