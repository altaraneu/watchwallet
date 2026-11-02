package com.altaran.watchwallet.core.pricing

import com.altaran.watchwallet.core.model.CandleInterval
import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class CandlePagingTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val minute = 60_000L

    @Test
    fun closesAskForTheWindowAndAreDatedAtTheirClose() = runTest {
        val urls = mutableListOf<Url>()
        val engine = MockEngine { request ->
            urls += request.url
            respond("""[[${10 * minute},"1","2","0.5","1.5"],[${11 * minute},"1.5","2","1","1.7"]]""", HttpStatusCode.OK, json)
        }
        val closes = ExchangePrices(createHttpClient(engine = engine), hosts = listOf("https://ex.test"))
            .closes("BTC", 1, Instant.fromEpochMilliseconds(11 * minute), Instant.fromEpochMilliseconds(12 * minute))
        assertEquals(1, urls.size)
        val parameters = urls.single().parameters
        assertEquals("BTCUSDT", parameters["symbol"])
        assertEquals("1m", parameters["interval"])
        assertEquals("${10 * minute}", parameters["startTime"])
        assertEquals("${11 * minute}", parameters["endTime"])
        assertEquals(listOf(11 * minute, 12 * minute), closes.map { it.time.toEpochMilliseconds() })
        assertEquals(1.7, closes.last().closeUsd)
    }

    @Test
    fun hourlyClosesUseTheHourlyInterval() = runTest {
        var interval: String? = null
        val engine = MockEngine { request ->
            interval = request.url.parameters["interval"]
            respond("[]", HttpStatusCode.OK, json)
        }
        ExchangePrices(createHttpClient(engine = engine), hosts = listOf("https://ex.test"))
            .closes("ETH", 60, Instant.fromEpochMilliseconds(0), Instant.fromEpochMilliseconds(minute))
        assertEquals("1h", interval)
    }

    @Test
    fun stablecoinsAndUnlistedAssetsNeedNoRequest() = runTest {
        var requests = 0
        val engine = MockEngine { _ ->
            requests++
            respond("""{"code":-1121,"msg":"Invalid symbol."}""", HttpStatusCode.BadRequest, json)
        }
        val exchange = ExchangePrices(createHttpClient(engine = engine), hosts = listOf("https://ex.test"))
        val from = Instant.fromEpochMilliseconds(0)
        val to = Instant.fromEpochMilliseconds(minute)
        assertTrue(exchange.closes("USDT", 1, from, to).isEmpty())
        assertEquals(0, requests)
        // Rejected once, then remembered.
        assertTrue(exchange.closes("KAS", 1, from, to).isEmpty())
        assertTrue(exchange.closes("KAS", 1, from, to).isEmpty())
        assertEquals(1, requests)
    }

    @Test
    fun binancePagesWithEndAndStartTime() = runTest {
        val urls = mutableListOf<Url>()
        val engine = MockEngine { request ->
            urls += request.url
            respond("""[[3600000,"1","2","0.5","1.5","10",3659999,"15"]]""", HttpStatusCode.OK, json)
        }
        val binance = BinanceMarketData(createHttpClient(engine = engine), restHosts = listOf("https://ex.test"))
        val older = binance.candles("BTC", CandleInterval.M1, beforeSeconds = 7200)
        assertEquals("7199999", urls.last().parameters["endTime"])
        assertEquals(null, urls.last().parameters["startTime"])
        binance.candles("BTC", CandleInterval.M1, afterSeconds = 7200)
        assertEquals("7200001", urls.last().parameters["startTime"])
        assertEquals(3600L, older.single().time)
    }

    private fun candle(time: Long, close: Double = 1.0) = OhlcCandle(time, 1.0, 1.0, 1.0, close)

    @Test
    fun mergeSortsAndNewerCandlesWin() {
        val merged = mergeCandles(listOf(candle(2), candle(3, close = 5.0)), listOf(candle(1), candle(3, close = 7.0)), maxCandles = 10, keepAround = 0)
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.time })
        assertEquals(7.0, merged.last().close)
    }

    @Test
    fun mergeDropsWhatIsFarthestFromTheView() {
        val all = (0L until 100L).map { candle(it) }
        val aroundStart = mergeCandles(all, emptyList(), maxCandles = 10, keepAround = 0)
        assertEquals(0L until 10L, aroundStart.first().time until aroundStart.last().time + 1)
        val aroundEnd = mergeCandles(all, emptyList(), maxCandles = 10, keepAround = 99)
        assertEquals(90L, aroundEnd.first().time)
        val middle = mergeCandles(all, emptyList(), maxCandles = 10, keepAround = 50)
        assertTrue(middle.any { it.time == 50L })
        assertEquals(10, middle.size)
    }

    private class NoPrices : PriceProvider {
        var calls = 0

        override suspend fun current(priceIds: Collection<String>, currency: String) = emptyMap<String, CurrentPrice>()

        override suspend fun history(priceId: String, currency: String, days: Int?): List<PricePoint> {
            calls++
            return emptyList()
        }

        override suspend fun markets(coinIds: Collection<String>, currency: String) = emptyList<MarketInfo>()

        override suspend fun search(query: String) = emptyList<CoinSearchResult>()
    }

    @Test
    fun assetsOutsideTheExchangeNeverReachTheFallback() = runTest {
        val fallback = NoPrices()
        val engine = MockEngine { _ -> error("no request expected") }
        val http = createHttpClient(engine = engine)
        val provider = LayeredPriceProvider(fallback, ExchangePrices(http, hosts = listOf("https://ex.test")), FxRates(http, urls = emptyList()))
        val points = provider.candleCloses("token:ethereum:0xunknown", "usd", 1, Instant.fromEpochMilliseconds(0), Instant.fromEpochMilliseconds(minute))
        assertTrue(points.isEmpty())
        assertEquals(0, fallback.calls)
    }
}
