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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ExchangePricesTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun unlistedSymbolDoesNotFailTheOthers() = runTest {
        val engine = MockEngine { request ->
            val symbols = request.url.parameters["symbols"].orEmpty()
            when {
                // The exchange rejects the whole batch when one symbol is unknown.
                "KASUSDT" in symbols -> respond("""{"code":-1121,"msg":"Invalid symbol."}""", HttpStatusCode.BadRequest, json)

                "BTCUSDT" in symbols -> respond("""[{"symbol":"BTCUSDT","lastPrice":"100000.0","openPrice":"98000.0"}]""", HttpStatusCode.OK, json)

                else -> respond("[]", HttpStatusCode.OK, json)
            }
        }
        val prices = ExchangePrices(createHttpClient(engine = engine), hosts = listOf("https://ex.test")).tickers(setOf("BTC", "KAS", "USDT"))
        assertEquals(100_000.0, prices.getValue("BTC").priceUsd)
        assertEquals(1.0, prices.getValue("USDT").priceUsd)
        assertNull(prices["KAS"])
    }

    @Test
    fun transientFailureDoesNotMarkSymbolsUnlisted() = runTest {
        var fail = true
        val engine = MockEngine { _ ->
            if (fail) {
                respond("""{"code":-1003,"msg":"Too many requests."}""", HttpStatusCode.Forbidden, json)
            } else {
                respond("""[{"symbol":"BTCUSDT","lastPrice":"100000.0","openPrice":"98000.0"}]""", HttpStatusCode.OK, json)
            }
        }
        val exchange = ExchangePrices(createHttpClient(engine = engine), hosts = listOf("https://ex.test"))
        assertFailsWith<PriceDataException> { exchange.tickers(setOf("BTC")) }
        fail = false
        assertEquals(100_000.0, exchange.tickers(setOf("BTC")).getValue("BTC").priceUsd)
    }

    @Test
    fun dailyClosesAreDatedAtTheEndOfTheDay() = runTest {
        val day = 86_400_000L
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val open = (now / day - 3) * day
        val current = (now / day) * day
        val engine = MockEngine { _ ->
            respond("""[[$open,"1","2","0.5","1.5"],[$current,"1.5","2","1","1.7"]]""", HttpStatusCode.OK, json)
        }
        val candles = ExchangePrices(createHttpClient(engine = engine), hosts = listOf("https://ex.test"))
            .history("BTC", hourly = false, limit = 2)
        // The day still in progress is left out; the finished one is dated at its close.
        assertEquals(1, candles.size)
        assertEquals(open + day, candles.single().time.toEpochMilliseconds())
        assertEquals(1.5, candles.single().closeUsd)
    }

    @Test
    fun tokensAreMatchedByContractNeverBySymbol() {
        assertEquals("SHIB", KnownAssets.symbolOf("token:ethereum:0x95ad61b0a150d79219dcf64e1e6cc01f0b64c4ce"))
        // A spam token copying a symbol has a different contract and stays unknown.
        assertNull(KnownAssets.symbolOf("token:ethereum:0x0000000000000000000000000000000000000bad"))
        assertEquals("BTC", KnownAssets.symbolOf("bitcoin"))
    }
}
