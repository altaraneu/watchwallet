package com.altaran.watchwallet.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Limits concurrency and request rate towards one API, with random jitter between requests.
 *
 * The jitter is also a privacy measure: requests for addresses derived from the same xpub are
 * not fired in a perfectly regular burst, which makes linking them by timing harder.
 */
class Throttle(
    maxConcurrent: Int,
    private val minInterval: Duration,
    private val maxJitter: Duration = 0.milliseconds,
    private val random: Random = Random.Default,
) {
    private val semaphore = Semaphore(maxConcurrent)
    private val mutex = Mutex()
    private var nextAllowed = 0L

    suspend fun <T> run(block: suspend () -> T): T = semaphore.withPermit {
        mutex.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            val jitter = if (maxJitter.inWholeMilliseconds > 0) random.nextLong(maxJitter.inWholeMilliseconds) else 0L
            val waitUntil = maxOf(now, nextAllowed) + jitter
            nextAllowed = waitUntil + minInterval.inWholeMilliseconds
            val wait = waitUntil - now
            if (wait > 0) delay(wait)
        }
        block()
    }
}

/** One [Throttle] per host: a slow or rate-limited API never delays requests to another one. */
class HostThrottles(private val maxConcurrent: Int, private val minInterval: Duration) {
    private val throttles = HashMap<String, Throttle>()
    private val lock = Mutex()

    suspend fun <T> run(host: String, block: suspend () -> T): T {
        val throttle = lock.withLock { throttles.getOrPut(host) { Throttle(maxConcurrent, minInterval) } }
        return throttle.run(block)
    }
}
