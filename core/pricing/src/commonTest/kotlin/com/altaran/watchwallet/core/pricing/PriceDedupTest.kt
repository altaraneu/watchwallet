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
            hosts += request.url.host
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
