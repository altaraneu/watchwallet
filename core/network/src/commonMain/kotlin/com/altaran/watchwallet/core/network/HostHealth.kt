package com.altaran.watchwallet.core.network

import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Every host of a request was failing recently, so no request was sent. */
class HostsUnavailableException(hosts: List<String>) : Exception("Hosts cooling down: ${hosts.size}")

/**
 * Remembers for the session which hosts are failing, so later calls do not wait for them. Each
 * consecutive failure extends the cool-down; a blocked client (HTTP 403, a WAF challenge) waits
 * longest because retrying soon cannot help. A success clears the host.
 */
class HostHealth(private val now: () -> Instant = { Clock.System.now() }) {
    private class Failure(val count: Int, val until: Instant)

    private val failures = HashMap<String, Failure>()
    private val lock = Mutex()

    /** Forgets all failures: a user-requested refresh tries every host again. */
    suspend fun reset() = lock.withLock { failures.clear() }

    suspend fun isCoolingDown(host: String): Boolean = lock.withLock { failures[host]?.let { now() < it.until } == true }

    suspend fun markOk(host: String) {
        lock.withLock { failures.remove(host) }
    }

    suspend fun markFailed(host: String, cause: Throwable) {
        lock.withLock {
            val count = (failures[host]?.count ?: 0) + 1
            failures[host] = Failure(count, now() + cooldown(count, cause))
        }
    }

    /**
     * Runs [block] on the first healthy host of [hosts]. Failures that say something about the
     * host try the next one; other failures (a 404 for an unknown account, an invalid symbol) are
     * rethrown at once. Hosts cooling down are skipped; when all are, the call fails immediately.
     */
    suspend fun <T> firstAvailable(
        hosts: List<String>,
        isHostFailure: (Throwable) -> Boolean = ::defaultIsHostFailure,
        block: suspend (String) -> T,
    ): T {
        val candidates = hosts.filterNot { isCoolingDown(it) }
        if (candidates.isEmpty()) throw HostsUnavailableException(hosts)
        var last: Exception? = null
        for (host in candidates) {
            try {
                val result = block(host)
                markOk(host)
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                if (!isHostFailure(e)) throw e
                markFailed(host, e)
                last = e
            }
        }
        throw last ?: HostsUnavailableException(hosts)
    }

    private fun cooldown(count: Int, cause: Throwable): Duration {
        if ((cause as? ResponseException)?.response?.status?.value == HTTP_FORBIDDEN) return BLOCKED_COOLDOWN
        var result = BASE_COOLDOWN
        repeat(count - 1) { result = (result * COOLDOWN_GROWTH).coerceAtMost(MAX_COOLDOWN) }
        return result
    }

    companion object {
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val COOLDOWN_GROWTH = 4
        private val BASE_COOLDOWN = 30.seconds
        private val MAX_COOLDOWN = 10.minutes
        private val BLOCKED_COOLDOWN = 30.minutes

        /** Client errors other than 403, 408 and 429 are about the request, not the host. */
        fun defaultIsHostFailure(cause: Throwable): Boolean {
            val status = (cause as? ResponseException)?.response?.status?.value ?: return true
            return status !in 400..499 || status == HTTP_FORBIDDEN || status == HTTP_TIMEOUT || status == HTTP_TOO_MANY_REQUESTS
        }
    }
}
