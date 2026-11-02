package com.altaran.watchwallet.core.pricing

import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun row(id: String) = MarketInfo(id, id, id, 1.0, null, null, null, emptyList())

private class CountingProvider : PriceProvider {
    var marketCalls = 0
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun current(priceIds: Collection<String>, currency: String) = emptyMap<String, CurrentPrice>()

    override suspend fun history(priceId: String, currency: String, days: Int?) = emptyList<PricePoint>()

    override suspend fun markets(coinIds: Collection<String>, currency: String): List<MarketInfo> {
        marketCalls++
        gate?.await()
        return coinIds.map(::row)
    }

    override suspend fun search(query: String) = emptyList<CoinSearchResult>()
}

class PriceDedupTest {
    private val offline = createHttpClient(engine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) })
    private var time = Instant.fromEpochSeconds(1_000)
    private val fallback = CountingProvider()
    private val layered = LayeredPriceProvider(fallback, ExchangePrices(offline, hosts = listOf("https://ex.test")), FxRates(offline, listOf("https://fx.test"))) { time }

    @Test
    fun marketRequestsAreSharedAcrossScreens() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        fallback.gate = gate
        val callers = List(3) { async { layered.markets(listOf("ethereum", "bitcoin"), "usd") } }
        delay(100)
        gate.complete(Unit)
        callers.forEach { assertEquals(2, it.await().size) }
        assertEquals(1, fallback.marketCalls)

        assertEquals(listOf("bitcoin"), layered.markets(listOf("bitcoin"), "usd").map { it.priceId }, "a coin detail uses the fresh list")
        assertEquals(1, fallback.marketCalls)

        time += 31.seconds
        layered.markets(listOf("bitcoin"), "usd")
        assertEquals(2, fallback.marketCalls)
    }

    @Test
    fun coinGeckoFailureOfCoinsStillAsksForTokensAndAFailedTokenIsAskedAgain() = runBlocking {
        val usdc = "token:ethereum:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        var tokenCalls = 0
        var tokenStatus = HttpStatusCode.NotFound
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/simple/price") -> {
                    respond("x", HttpStatusCode.NotFound)
                }

                else -> {
                    tokenCalls++
                    if (tokenStatus == HttpStatusCode.OK) {
                        respond("""{"${usdc.substringAfterLast(':')}":{"usd":1.0}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        respond("x", tokenStatus)
                    }
                }
            }
        }
        val provider = CoinGeckoProvider(createHttpClient(engine = engine), "https://cg.test/api/v3")
        assertFailsWith<PriceDataException> { provider.current(listOf("bitcoin", usdc), "usd") }
        assertEquals(1, tokenCalls, "coins failing does not stop tokens")

        tokenStatus = HttpStatusCode.OK
        val batches = mutableListOf<Map<String, CurrentPrice>>()
        provider.currentInBatches(listOf(usdc), "usd") { batches += it }
        assertEquals(2, tokenCalls, "a failed request is no proof of a missing price")
        assertEquals(1.0, batches.single().getValue(usdc).price)

        provider.currentInBatches(listOf(usdc), "usd") { batches += it }
        assertEquals(2, tokenCalls, "a token priced a moment ago is not asked again")
    }

    @Test
    fun rateLimitStopsTheRoundOfTokens() = runBlocking {
        var tokenCalls = 0
        val engine = MockEngine {
            tokenCalls++
            respond("x", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3600"))
        }
        val provider = CoinGeckoProvider(createHttpClient(engine = engine), "https://cg.test/api/v3")
        val tokens = listOf("token:ethereum:0x01", "token:ethereum:0x02", "token:base:0x03")
        assertTrue(provider.current(tokens, "usd").isEmpty())
        assertEquals(1, tokenCalls)
    }

    @Test
    fun exchangeFailsOverAndRemembersTheFailingHost() = runBlocking {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            if ("ticker" in request.url.encodedPath) hosts += request.url.host
            if (request.url.host == "down.test") {
                respond("", HttpStatusCode.Forbidden)
            } else {
                respond(
                    """[{"symbol":"BTCUSDT","lastPrice":"10","openPrice":"10"}]""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val exchange = ExchangePrices(createHttpClient(engine = engine), hosts = listOf("https://down.test", "https://up.test"))
        assertEquals(10.0, exchange.tickers(setOf("BTC")).getValue("BTC").priceUsd)
        exchange.tickers(setOf("BTC"))
        assertEquals(listOf("down.test", "up.test", "up.test"), hosts)
    }

    // ------------------------------------------------------------ frozen or diverging exchange

    private class Market(
        var close: Long = 2_000_000,
        var count: Int = 5,
        var lastId: Long = 1,
        var status: String = "TRADING",
        var price: String = "100.0",
    )

    private fun exchange(market: Market) = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            request.url.encodedPath.endsWith("exchangeInfo") -> respond("""{"symbols":[{"symbol":"BTCUSDT","status":"${market.status}"}]}""", HttpStatusCode.OK, json)

            request.url.encodedPath.endsWith("klines") -> {
                respond("""[[${(market.close - 3_600) * 1000},"1","2","0.5","50"]]""", HttpStatusCode.OK, json)
            }

            else -> respond(
                """[{"symbol":"BTCUSDT","lastPrice":"${market.price}","openPrice":"90.0","closeTime":${market.close * 1000},"count":${market.count},"lastId":${market.lastId}}]""",
                HttpStatusCode.OK,
                json,
            )
        }
    }

    @Test
    fun frozenTickersAreLeftOut() = runBlocking {
        class Case(val name: String, val market: Market, val priced: Boolean)
        val now = Instant.fromEpochSeconds(2_000_000)
        listOf(
            Case("live", Market(), true),
            Case("trading halted", Market(status = "HALT"), false),
            Case("ticker not updated for an hour", Market(close = 2_000_000 - 3_600), false),
            Case("no trades in 24 h", Market(count = 0), false),
        ).forEach { case ->
            val prices = ExchangePrices(createHttpClient(engine = exchange(case.market)), hosts = listOf("https://ex.test")) { now }
            assertEquals(case.priced, "BTC" in prices.tickers(setOf("BTC")), case.name)
        }
    }

    @Test
    fun aTradeIdThatStopsMovingFreezesThePrice() = runBlocking {
        var now = Instant.fromEpochSeconds(2_000_000)
        val market = Market()
        val prices = ExchangePrices(createHttpClient(engine = exchange(market)), hosts = listOf("https://ex.test")) { now }
        assertTrue("BTC" in prices.tickers(setOf("BTC")))
        now += 10.minutes
        market.close = now.epochSeconds
        assertTrue("BTC" in prices.tickers(setOf("BTC")), "a quiet moment is fine")
        now += 10.minutes
        market.close = now.epochSeconds
        assertTrue("BTC" !in prices.tickers(setOf("BTC")), "20 minutes without a trade are not")
        market.lastId = 2
        assertTrue("BTC" in prices.tickers(setOf("BTC")), "trading again")
    }

    @Test
    fun frozenCandlesAreLeftOut() = runBlocking {
        val now = Instant.fromEpochSeconds(2_000_000)
        val market = Market(close = 2_000_000 - 5 * 3_600)
        val prices = ExchangePrices(createHttpClient(engine = exchange(market)), hosts = listOf("https://ex.test")) { now }
        assertTrue(prices.history("BTC", hourly = true, limit = 10).isEmpty(), "5 hours old")
        market.close = 2_000_000 - 3_600
        assertTrue(prices.history("BTC", hourly = true, limit = 10).isNotEmpty())
    }

    private class FallbackPrices(var price: Double = 100.0) : PriceProvider {
        val asked = mutableListOf<Collection<String>>()

        override suspend fun current(priceIds: Collection<String>, currency: String): Map<String, CurrentPrice> {
            asked += priceIds
            return priceIds.associateWith { CurrentPrice(it, currency, price, -1.0) }
        }

        override suspend fun history(priceId: String, currency: String, days: Int?) = listOf(PricePoint(Instant.fromEpochSeconds(1), 7.0))

        override suspend fun markets(coinIds: Collection<String>, currency: String) = emptyList<MarketInfo>()

        override suspend fun search(query: String) = emptyList<CoinSearchResult>()
    }

    private fun layered(market: Market, fallback: FallbackPrices, now: () -> Instant): LayeredPriceProvider {
        val client = createHttpClient(engine = exchange(market))
        return LayeredPriceProvider(fallback, ExchangePrices(client, hosts = listOf("https://ex.test"), now = now), FxRates(client, listOf("https://fx.test")), now)
    }

    @Test
    fun frozenExchangeFallsBackToTheNextSource() = runBlocking {
        val now = Instant.fromEpochSeconds(2_000_000)
        val fallback = FallbackPrices(price = 120.0)
        val prices = layered(Market(status = "HALT", price = "1.0"), fallback) { now }.current(listOf("bitcoin"), "usd")
        assertEquals(120.0, prices.getValue("bitcoin").price)
        assertEquals(-1.0, prices.getValue("bitcoin").change24hPercent)
    }

    @Test
    fun frozenHistoryFallsBackToTheNextSource() = runBlocking {
        val now = Instant.fromEpochSeconds(2_000_000)
        val points = layered(Market(close = 2_000_000 - 30 * 86_400), FallbackPrices()) { now }.history("bitcoin", "usd", 7)
        assertEquals(listOf(7.0), points.map { it.price })
    }

    @Test
    fun clearOutlierTakesTheOtherSourcesPriceForAWhile() = runBlocking {
        var now = Instant.fromEpochSeconds(2_000_000)
        val market = Market(price = "150.0")
        val fallback = FallbackPrices(price = 100.0)
        val provider = layered(market, fallback) { now }
        val batches = mutableListOf<Map<String, CurrentPrice>>()

        provider.currentInBatches(listOf("bitcoin"), "usd") { batches += it }
        assertEquals(listOf(150.0, 100.0), batches.map { it.getValue("bitcoin").price }, "shown at once, then corrected")

        batches.clear()
        now += 1.minutes
        market.lastId++
        market.close = now.epochSeconds
        provider.currentInBatches(listOf("bitcoin"), "usd") { batches += it }
        assertEquals(listOf(100.0), batches.map { it.getValue("bitcoin").price }, "the exchange is not used meanwhile")
    }

    @Test
    fun smallDifferencesAreNormalAndTheCheckIsRare() = runBlocking {
        var now = Instant.fromEpochSeconds(2_000_000)
        val market = Market(price = "105.0")
        val fallback = FallbackPrices(price = 100.0)
        val provider = layered(market, fallback) { now }
        assertEquals(105.0, provider.current(listOf("bitcoin"), "usd").getValue("bitcoin").price)
        now += 1.minutes
        market.lastId++
        market.close = now.epochSeconds
        provider.current(listOf("bitcoin"), "usd")
        assertEquals(1, fallback.asked.size, "one request per check, not one per refresh")
    }

    @Test
    fun fxRequestsAreSharedAndAStaleRateIsBetterThanNone() = runBlocking {
        var calls = 0
        var failing = false
        val engine = MockEngine {
            calls++
            delay(50)
            if (failing) {
                respond("", HttpStatusCode.ServiceUnavailable)
            } else {
                respond("""{"rates":{"CZK":24.0}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val fx = FxRates(createHttpClient(engine = engine), listOf("https://fx.test"))
        List(4) { async { fx.rate("USD", "CZK") } }.forEach { assertEquals(24.0, it.await()) }
        assertEquals(1, calls)
        failing = true
        assertEquals(24.0, fx.rate("USD", "CZK"), "cached")
    }
}
