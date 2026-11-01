package com.altaran.watchwallet.core.pricing

import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CoinGeckoProviderTest {
    private val engine = MockEngine { request ->
        val body = when {
            request.url.encodedPath.endsWith("/simple/price") -> {
                """{"bitcoin":{"czk":2000000.5,"czk_24h_change":-1.5},"ethereum":{"czk":70000}}"""
            }

            request.url.encodedPath.contains("/simple/token_price/ethereum") -> {
                """{"0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48":{"czk":23.1}}"""
            }

            request.url.encodedPath.endsWith("/coins/bitcoin/market_chart") -> {
                """{"prices":[[1704067200000,1000000.0],[1704153600000,1100000.0]]}"""
            }

            else -> {
                error("Unexpected ${request.url}")
            }
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    private val provider = CoinGeckoProvider(createHttpClient(engine = engine), "https://cg.test/api/v3")

    @Test
    fun currentPricesForCoinsAndTokens() = runTest {
        val usdc = "token:ethereum:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        val prices = provider.current(listOf("bitcoin", "ethereum", usdc), "czk")
        assertEquals(2000000.5, prices.getValue("bitcoin").price)
        assertEquals(-1.5, prices.getValue("bitcoin").change24hPercent)
        assertEquals(null, prices.getValue("ethereum").change24hPercent)
        assertEquals(23.1, prices.getValue(usdc).price)
    }

    @Test
    fun history() = runTest {
        val points = provider.history("bitcoin", "czk", 365)
        assertEquals(2, points.size)
        assertEquals(1100000.0, points.last().price)
    }
}
