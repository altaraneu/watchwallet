package com.altaran.watchwallet.core.pricing

import com.altaran.watchwallet.core.network.SingleFlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Prices from several keyless sources, fastest first:
 *
 * 1. Exchange market data ([ExchangePrices], Binance) for well-known coins and tokens: one
 *    request returns the current price of every listed asset; one request per asset returns
 *    years of daily (or weeks of hourly) history.
 * 2. CoinGecko ([fallback]) for everything else (long-tail tokens) and for search and market
 *    caps. Its keyless tier is slow and strictly rate limited.
 *
 * Exchange prices are in USDT (treated as USD) and converted to the requested currency with
 * [fx] (fiat, ECB rates) or with the BTC/ETH price (crypto quote currencies). History is
 * converted with today's rate, like the exchange candles.
 */
class LayeredPriceProvider(
    private val fallback: PriceProvider,
    private val exchange: ExchangePrices,
    private val fx: FxRates,
    private val coinGecko: CoinGeckoUsage = CoinGeckoUsage(),
    private val now: () -> Instant = { Clock.System.now() },
) : PriceProvider {
    private class MarketRow(val info: MarketInfo, val at: Instant)

    /** Last rows by `currency|id`; fresh ones spare a request when another screen asks the same. */
    private val marketRows = HashMap<String, MarketRow>()
    private val marketRowsLock = Mutex()
    private val marketsFlight = SingleFlight<String, List<MarketInfo>>()
    private val trustLock = Mutex()
    private var lastCrossCheck: Instant? = null

    /** Price ids whose exchange price is far from the rest of the market. */
    private val distrustedUntil = HashMap<String, Instant>()

    override suspend fun current(priceIds: Collection<String>, currency: String): Map<String, CurrentPrice> {
        val result = HashMap<String, CurrentPrice>()
        currentInBatches(priceIds, currency) { result += it }
        return result
    }

    /** Exchange prices first, at once; a failing source only leaves its assets without an update. */
    override suspend fun currentInBatches(priceIds: Collection<String>, currency: String, onBatch: suspend (Map<String, CurrentPrice>) -> Unit) {
        val ids = priceIds.distinct()
        val symbols = ids.associateWith { KnownAssets.symbolOf(it) }.filterValues { it != null }.mapValues { it.value!! }
        val distrusted = trustLock.withLock { distrustedUntil.filterValues { now() < it }.keys }
        val trusted = symbols.filterKeys { it !in distrusted }
        val fromExchange = runCatching { exchangeCurrent(trusted, currency) }.getOrElse { e ->
            if (e is CancellationException) throw e
            emptyMap()
        }
        if (fromExchange.isNotEmpty()) {
            onBatch(fromExchange)
            coinGecko.replacedCurrent(fromExchange.keys)
        }
        val rest = ids.filter { it !in fromExchange }
        try {
            if (rest.isNotEmpty()) {
                fallback.currentInBatches(rest, currency) { batch ->
                    onBatch(batch)
                    coinGecko.servedCurrent(batch.keys)
                }
            }
            crossCheck(fromExchange, currency, onBatch)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            // Stored prices stay; the next refresh tries again.
        }
    }

    /**
     * An exchange can keep trading far from the rest of the market (after a hack, say), which no
     * status shows. Now and then its prices are compared with the fallback's in one request; assets
     * clearly apart take the fallback's price, and stay with it for a while.
     */
    private suspend fun crossCheck(fromExchange: Map<String, CurrentPrice>, currency: String, onBatch: suspend (Map<String, CurrentPrice>) -> Unit) {
        val time = now()
        val checkable = fromExchange.filterKeys { KnownAssets.coinIdOf(it) !in STABLE_COINS && KnownAssets.coinIdOf(it) != null }
        val due = trustLock.withLock {
            val isDue = checkable.isNotEmpty() && lastCrossCheck?.let { time - it < CROSS_CHECK_INTERVAL } != true
            if (isDue) lastCrossCheck = time
            isDue
        }
        if (!due) return
        val reference = fallback.current(checkable.keys.mapNotNull(KnownAssets::coinIdOf).distinct(), currency)
        val outliers = checkable.mapNotNull { (id, price) ->
            val other = reference[KnownAssets.coinIdOf(id)]?.takeIf { it.price > 0 } ?: return@mapNotNull null
            val apart = kotlin.math.abs(price.price - other.price) / other.price > MAX_SOURCE_DIFFERENCE
            if (apart) id to CurrentPrice(id, currency, other.price, other.change24hPercent) else null
        }.toMap()
        if (outliers.isEmpty()) return
        trustLock.withLock { outliers.keys.forEach { distrustedUntil[it] = time + DISTRUST_PERIOD } }
        onBatch(outliers)
        coinGecko.servedCurrent(outliers.keys)
    }

    override suspend fun history(priceId: String, currency: String, days: Int?): List<PricePoint> {
        val symbol = KnownAssets.symbolOf(priceId)
        if (symbol != null) {
            val points = runCatching { exchangeHistory(symbol, currency, days) }.getOrElse { e ->
                if (e is CancellationException) throw e
                emptyList()
            }
            if (points.isNotEmpty()) return points
        }
        val points = fallback.history(priceId, currency, days)
        if (points.isNotEmpty()) coinGecko.servedHistory(priceId)
        return points
    }

    /** Screens asking at the same time, or moments apart, share one request. */
    override suspend fun markets(coinIds: Collection<String>, currency: String): List<MarketInfo> {
        val ids = coinIds.distinct().sorted()
        val cached = marketRowsLock.withLock { ids.mapNotNull { id -> marketRows["$currency|$id"] } }
        if (cached.size == ids.size && cached.all { now() - it.at < MARKETS_FRESH }) return cached.map { it.info }
        return marketsFlight.run(currency + "|" + ids.joinToString(",")) { loadMarkets(ids, currency, cached) }
    }

    private suspend fun loadMarkets(ids: List<String>, currency: String, cached: List<MarketRow>): List<MarketInfo> {
        // CoinGecko has names and market caps but is often slow; do not let it block the list.
        val rich = withTimeoutOrNull(MARKETS_TIMEOUT) { runCatching { fallback.markets(ids, currency) }.getOrNull() }
        val rows = rich?.takeIf { it.isNotEmpty() }?.map { it.copy(fromCoinGecko = true) }
            ?: runCatching { exchangeMarkets(ids, currency) }.getOrDefault(emptyList())
        if (rows.isEmpty()) {
            return cached.map { it.info }.ifEmpty { throw PriceDataException("No market data source available") }
        }
        val at = now()
        marketRowsLock.withLock { rows.forEach { marketRows["$currency|${it.priceId}"] = MarketRow(it, at) } }
        return rows
    }

    override suspend fun search(query: String): List<CoinSearchResult> = fallback.search(query)

    override suspend fun historyBefore(priceId: String, currency: String, before: Instant): List<PricePoint> {
        val symbol = KnownAssets.symbolOf(priceId) ?: return emptyList()
        val conversion = conversion(currency) ?: return emptyList()
        val end = Instant.fromEpochMilliseconds(before.toEpochMilliseconds() - 1)
        val candles = exchange.history(symbol, hourly = false, limit = ExchangePrices.MAX_CANDLES, endTime = end)
        if (conversion.needs.isEmpty()) return candles.map { PricePoint(it.time, it.closeUsd * conversion.fiatFactor) }
        val quote = exchange.history(conversion.needs.single(), hourly = false, limit = ExchangePrices.MAX_CANDLES, endTime = end).associateBy { it.time }
        return candles.mapNotNull { c -> quote[c.time]?.closeUsd?.takeIf { it > 0 }?.let { PricePoint(c.time, c.closeUsd / it) } }
    }

    /** Exchange candles only: never a per-asset CoinGecko call, its keyless tier could not bear it. */
    override suspend fun candleCloses(priceId: String, currency: String, stepMinutes: Int, from: Instant, to: Instant): List<PricePoint> {
        val symbol = KnownAssets.symbolOf(priceId) ?: return emptyList()
        // An exchange found to be far off the market does not refine the chart either.
        if (trustLock.withLock { distrustedUntil[priceId]?.let { now() < it } == true }) return emptyList()
        val conversion = conversion(currency) ?: return emptyList()
        val candles = exchange.closes(symbol, stepMinutes, from, to)
        if (conversion.needs.isEmpty()) return candles.map { PricePoint(it.time, it.closeUsd * conversion.fiatFactor) }
        val quote = exchange.closes(conversion.needs.single(), stepMinutes, from, to).associateBy { it.time }
        return candles.mapNotNull { c -> quote[c.time]?.closeUsd?.takeIf { it > 0 }?.let { PricePoint(c.time, c.closeUsd / it) } }
    }

    // ---------------------------------------------------------------- exchange helpers

    private suspend fun exchangeCurrent(symbols: Map<String, String>, currency: String): Map<String, CurrentPrice> {
        if (symbols.isEmpty()) return emptyMap()
        val conversion = conversion(currency) ?: return emptyMap()
        val tickers = exchange.tickers(symbols.values.toSet() + conversion.needs)
        val factor = conversion.factor(tickers) ?: return emptyMap()
        return symbols.mapNotNull { (id, symbol) ->
            val ticker = tickers[symbol] ?: return@mapNotNull null
            id to CurrentPrice(id, currency, ticker.priceUsd * factor, ticker.change24hPercent)
        }.toMap()
    }

    private suspend fun exchangeHistory(symbol: String, currency: String, days: Int?): List<PricePoint> {
        val conversion = conversion(currency) ?: return emptyList()
        val hourly = days != null && days <= MAX_HOURLY_DAYS
        val limit = if (hourly) (days * 24).coerceAtMost(ExchangePrices.MAX_CANDLES) else ExchangePrices.MAX_CANDLES
        val candles = exchange.history(symbol, hourly, limit)
        if (candles.isEmpty()) return emptyList()
        return if (conversion.needs.isEmpty()) {
            val factor = conversion.fiatFactor
            candles.map { PricePoint(it.time, it.closeUsd * factor) }
        } else {
            // Crypto quote currency: divide by the quote asset's price at the same time.
            val quote = exchange.history(conversion.needs.single(), hourly, limit).associateBy { it.time }
            candles.mapNotNull { c -> quote[c.time]?.closeUsd?.takeIf { it > 0 }?.let { PricePoint(c.time, c.closeUsd / it) } }
        }
    }

    private suspend fun exchangeMarkets(coinIds: Collection<String>, currency: String): List<MarketInfo> {
        val ids = coinIds.filter { KnownAssets.symbolOf(it) != null }
        val prices = exchangeCurrent(ids.associateWith { KnownAssets.symbolOf(it)!! }, currency)
        return coroutineScope {
            ids.mapNotNull { id -> prices[id]?.let { id to it } }.map { (id, price) ->
                async {
                    val symbol = KnownAssets.symbolOf(id)!!
                    val week = runCatching { exchangeHistory(symbol, currency, 7) }.getOrDefault(emptyList())
                    MarketInfo(
                        priceId = id,
                        symbol = KnownAssets.displaySymbolOf(id) ?: symbol,
                        name = KnownAssets.nameOf(id) ?: symbol,
                        price = price.price,
                        change24hPercent = price.change24hPercent,
                        change7dPercent = week.firstOrNull()?.price?.takeIf { it > 0 }?.let { (price.price - it) / it * PERCENT },
                        marketCap = null,
                        sparkline7d = week.map { it.price },
                    )
                }
            }.awaitAll()
        }
    }

    /** How to turn a USD price into [currency]. */
    private class Conversion(val fiatFactor: Double, val needs: Set<String>) {
        fun factor(tickers: Map<String, ExchangeTicker>): Double? =
            if (needs.isEmpty()) fiatFactor else tickers[needs.single()]?.priceUsd?.takeIf { it > 0 }?.let { 1.0 / it }
    }

    private suspend fun conversion(currency: String): Conversion? = when (val code = currency.uppercase()) {
        "USD" -> Conversion(1.0, emptySet())
        "BTC", "ETH" -> Conversion(1.0, setOf(code))
        else -> runCatching { fx.rate("USD", code) }.getOrNull()?.takeIf { it > 0 }?.let { Conversion(it, emptySet()) }
    }

    private companion object {
        val MARKETS_TIMEOUT = 6.seconds
        val MARKETS_FRESH = 30.seconds
        val CROSS_CHECK_INTERVAL = 5.minutes
        val DISTRUST_PERIOD = 30.minutes
        const val MAX_SOURCE_DIFFERENCE = 0.1
        val STABLE_COINS = setOf("tether", "usd-coin")
        const val PERCENT = 100.0

        /** Hourly exchange candles cover at most 1000 hours (about 41 days). */
        const val MAX_HOURLY_DAYS = 41
    }
}

/** A price point of exchange history, in USD. */
data class ExchangeCandle(val time: Instant, val closeUsd: Double)

data class ExchangeTicker(val priceUsd: Double, val change24hPercent: Double?)

/**
 * Coins and tokens the exchanges list, keyed by CoinGecko id or token price id. Tokens are
 * matched by contract only (never by symbol, which spam tokens copy).
 */
object KnownAssets {
    private class Entry(val symbol: String, val name: String, val display: String = symbol)

    private val coins = mapOf(
        "bitcoin" to Entry("BTC", "Bitcoin"),
        "ethereum" to Entry("ETH", "Ethereum"),
        "tether" to Entry("USDT", "Tether"),
        "usd-coin" to Entry("USDC", "USDC"),
        "binancecoin" to Entry("BNB", "BNB"),
        "solana" to Entry("SOL", "Solana"),
        "ripple" to Entry("XRP", "XRP"),
        "cardano" to Entry("ADA", "Cardano"),
        "dogecoin" to Entry("DOGE", "Dogecoin"),
        "tron" to Entry("TRX", "TRON"),
        "the-open-network" to Entry("TON", "Toncoin"),
        "polkadot" to Entry("DOT", "Polkadot"),
        "litecoin" to Entry("LTC", "Litecoin"),
        "bitcoin-cash" to Entry("BCH", "Bitcoin Cash"),
        "stellar" to Entry("XLM", "Stellar"),
        "near" to Entry("NEAR", "NEAR Protocol"),
        "aptos" to Entry("APT", "Aptos"),
        "sui" to Entry("SUI", "Sui"),
        "cosmos" to Entry("ATOM", "Cosmos Hub"),
        "osmosis" to Entry("OSMO", "Osmosis"),
        "celestia" to Entry("TIA", "Celestia"),
        "injective-protocol" to Entry("INJ", "Injective"),
        "kusama" to Entry("KSM", "Kusama"),
        "algorand" to Entry("ALGO", "Algorand"),
        "tezos" to Entry("XTZ", "Tezos"),
        "hedera-hashgraph" to Entry("HBAR", "Hedera"),
        "avalanche-2" to Entry("AVAX", "Avalanche"),
        "polygon-ecosystem-token" to Entry("POL", "POL (ex-MATIC)"),
        "matic-network" to Entry("POL", "Polygon", display = "MATIC"),
        "shiba-inu" to Entry("SHIB", "Shiba Inu"),
        "chiliz" to Entry("CHZ", "Chiliz"),
        "ankr" to Entry("ANKR", "Ankr"),
        "chainlink" to Entry("LINK", "Chainlink"),
        "uniswap" to Entry("UNI", "Uniswap"),
        "arbitrum" to Entry("ARB", "Arbitrum"),
        "optimism" to Entry("OP", "Optimism"),
        "ethereum-classic" to Entry("ETC", "Ethereum Classic"),
        "celo" to Entry("CELO", "Celo"),
        "sei-network" to Entry("SEI", "Sei"),
        "pepe" to Entry("PEPE", "Pepe"),
        "wrapped-bitcoin" to Entry("WBTC", "Wrapped Bitcoin"),
    )

    /** Token price id (`token:<platform>:<contract>`, EVM contracts lowercase) → CoinGecko id. */
    private val tokens = mapOf(
        "token:ethereum:0xdac17f958d2ee523a2206206994597c13d831ec7" to "tether",
        "token:ethereum:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" to "usd-coin",
        "token:ethereum:0x95ad61b0a150d79219dcf64e1e6cc01f0b64c4ce" to "shiba-inu",
        "token:ethereum:0x3506424f91fd33084466f402d5d97f05f8e3b4af" to "chiliz",
        "token:ethereum:0x8290333cef9e6d528dd5618fb97a76f268f3edd4" to "ankr",
        "token:ethereum:0x514910771af9ca656af840dff83e8264ecf986ca" to "chainlink",
        "token:ethereum:0x1f9840a85d5af5bf1d1762f925bdaddc4201f984" to "uniswap",
        "token:ethereum:0x455e53cbb86018ac2b8092fdcd39d8444affc3f6" to "polygon-ecosystem-token",
        "token:ethereum:0x7d1afa7b718fb893db30a3abc0cfc608aacfebb0" to "matic-network",
        "token:ethereum:0x6982508145454ce325ddbe47a25d4ec3d2311933" to "pepe",
        "token:ethereum:0x2260fac5e5542a773aa44fbcfedf7c193bc2c599" to "wrapped-bitcoin",
        "token:arbitrum-one:0xaf88d065e77c8cc2239327c5edb3a432268e5831" to "usd-coin",
        "token:arbitrum-one:0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9" to "tether",
        "token:arbitrum-one:0x912ce59144191c1204e64559fe8253a0e49e6548" to "arbitrum",
        "token:base:0x833589fcd6edb6e08f4c7c32d4f71b54bda02913" to "usd-coin",
        "token:optimistic-ethereum:0x0b2c639c533813f4aa9d7837caf62653d097ff85" to "usd-coin",
        "token:optimistic-ethereum:0x4200000000000000000000000000000000000042" to "optimism",
        "token:polygon-pos:0x3c499c542cef5e3811e1192ce70d8cc03d5c3359" to "usd-coin",
        "token:polygon-pos:0xc2132d05d31c914a87c6611c10748aeb04b58e8f" to "tether",
        "token:binance-smart-chain:0x55d398326f99059ff775485246999027b3197955" to "tether",
        "token:binance-smart-chain:0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d" to "usd-coin",
        "token:tron:TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t" to "tether",
        "token:solana:EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "usd-coin",
        "token:solana:Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to "tether",
    )

    private fun entry(priceId: String): Entry? = coins[priceId] ?: tokens[priceId]?.let { coins[it] }

    /** CoinGecko coin id for a price id: itself for coins, the mapped coin for known tokens. */
    fun coinIdOf(priceId: String): String? = if (priceId.startsWith("token:")) tokens[priceId] else priceId

    /** Exchange base symbol (BTC, ETH…) or `null` when no exchange lists the asset. */
    fun symbolOf(priceId: String): String? = entry(priceId)?.symbol

    fun displaySymbolOf(priceId: String): String? = entry(priceId)?.display

    fun nameOf(priceId: String): String? = entry(priceId)?.name
}
