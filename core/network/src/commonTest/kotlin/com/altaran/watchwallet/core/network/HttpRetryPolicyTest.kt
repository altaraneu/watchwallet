package com.altaran.watchwallet.core.network

import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HttpRetryPolicyTest {
    private class Case(val name: String, val status: Int, val retryAfter: String? = null, val calls: Int)

    @Test
    fun requestsPerStatus() = runBlocking {
        listOf(
            Case("400 is final", 400, calls = 1),
            Case("403 is final", 403, calls = 1),
            Case("404 is final", 404, calls = 1),
            Case("500 retries once", 500, calls = 2),
            Case("502 retries once", 502, calls = 2),
            Case("503 retries once", 503, calls = 2),
            Case("429 with a short wait retries once", 429, retryAfter = "0", calls = 2),
            Case("429 with a long wait is final", 429, retryAfter = "3600", calls = 1),
        ).forEach { case ->
            var calls = 0
            val engine = MockEngine {
                calls++
                val headers = case.retryAfter?.let { headersOf(HttpHeaders.RetryAfter, it) } ?: headersOf()
                respond("x", HttpStatusCode.fromValue(case.status), headers)
            }
            assertFailsWith<ResponseException>(case.name) { createHttpClient(engine = engine).get("https://a.test/x") }
            assertEquals(case.calls, calls, case.name)
        }
    }

    @Test
    fun connectionErrorRetriesOnceAndThenSucceeds() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            if (++calls == 1) throw IOException("reset")
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        createHttpClient(engine = engine).get("https://a.test/x").body<JsonObject>()
        assertEquals(2, calls)
    }

    @Test
    fun redirectsAreNotFollowed() = runBlocking {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            hosts += request.url.host
            respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://unlisted.test/x"))
        }
        assertFailsWith<ResponseException> { createHttpClient(engine = engine).get("https://a.test/x") }
        assertEquals(listOf("a.test"), hosts)
    }

    @Test
    fun delays() {
        assertEquals(500, HttpRetryPolicy.delayMillis(503, null))
        assertEquals(500, HttpRetryPolicy.delayMillis(null, null))
        assertEquals(2_000, HttpRetryPolicy.delayMillis(429, null))
        assertEquals(3_000, HttpRetryPolicy.delayMillis(429, "3"))
        assertEquals(2_000, HttpRetryPolicy.delayMillis(429, "Wed, 21 Oct 2026 07:28:00 GMT"), "dates use the default")
        assertEquals(10_000, HttpRetryPolicy.delayMillis(429, "11"), "capped")
    }

    @Test
    fun timeoutsAreNotRetried() {
        assertFalse(HttpRetryPolicy.shouldRetry(io.ktor.client.network.sockets.ConnectTimeoutException("slow")))
        assertTrue(HttpRetryPolicy.shouldRetry(IOException("reset")))
    }
}

class HostHealthTest {
    private var time = Instant.fromEpochSeconds(1_000)
    private val health = HostHealth { time }
    private val down = IOException("down")

    @Test
    fun failingHostIsSkippedByLaterCalls() = runTest {
        val tried = mutableListOf<String>()
        val call = suspend {
            health.firstAvailable(listOf("a", "b")) { host ->
                tried += host
                if (host == "a") throw down
                host
            }
        }
        assertEquals("b", call())
        assertEquals("b", call())
        assertEquals(listOf("a", "b", "b"), tried)
    }

    @Test
    fun cooledDownHostIsTriedAgain() = runTest {
        health.markFailed("a", down)
        assertTrue(health.isCoolingDown("a"))
        time += 31.seconds
        assertFalse(health.isCoolingDown("a"))
        assertEquals("ok", health.firstAvailable(listOf("a")) { "ok" })
    }

    @Test
    fun repeatedFailuresWaitLonger() = runTest {
        health.markFailed("a", down)
        health.markFailed("a", down)
        time += 31.seconds
        assertTrue(health.isCoolingDown("a"))
        time += 90.seconds
        assertFalse(health.isCoolingDown("a"))
    }

    @Test
    fun allHostsCoolingFailsWithoutARequest() = runTest {
        health.markFailed("a", down)
        assertFailsWith<HostsUnavailableException> { health.firstAvailable(listOf("a")) { error("must not run") } }
        health.reset()
        assertEquals("ok", health.firstAvailable(listOf("a")) { "ok" })
    }

    @Test
    fun requestErrorsDoNotBlameTheHost() = runBlocking {
        val client = createHttpClient(engine = MockEngine { respond("x", HttpStatusCode.NotFound) })
        val tried = mutableListOf<String>()
        assertFailsWith<ResponseException> {
            health.firstAvailable(listOf("a", "b")) { host ->
                tried += host
                client.get("https://$host.test/x")
            }
        }
        assertEquals(listOf("a"), tried)
        assertFalse(health.isCoolingDown("a"))
    }

    @Test
    fun forbiddenCoolsDownForLong() = runBlocking {
        val client = createHttpClient(engine = MockEngine { respond("x", HttpStatusCode.Forbidden) })
        assertFailsWith<ResponseException> { health.firstAvailable(listOf("a")) { client.get("https://a.test/x") } }
        time += 29.minutes
        assertTrue(health.isCoolingDown("a"))
    }
}

class SingleFlightTest {
    @Test
    fun concurrentCallersShareOneExecution() = runBlocking {
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val flight = SingleFlight<String, Int>()
        val callers = List(5) {
            async {
                flight.run("k") {
                    runs++
                    gate.await()
                    42
                }
            }
        }
        delay(100)
        gate.complete(Unit)
        assertEquals(List(5) { 42 }, callers.map { it.await() })
        assertEquals(1, runs)
        assertEquals(7, flight.run("k") { 7 }, "finished flights are forgotten")
    }
}
