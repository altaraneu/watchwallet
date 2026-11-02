package com.altaran.watchwallet.core.endpoints

import io.ktor.client.HttpClient
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlin.time.Clock

/** Persistence of the remote server list; implemented over the settings DataStore. */
interface EndpointConfigStore {
    suspend fun autoUpdateEnabled(): Boolean

    suspend fun storedEnvelope(): String?

    suspend fun saveEnvelope(envelope: String)

    suspend fun lastCheckSeconds(): Long?

    suspend fun saveLastCheckSeconds(seconds: Long)
}

enum class EndpointUpdateResult { DISABLED, NOT_DUE, UPDATED, UNCHANGED, FAILED }

/**
 * Downloads the signed server list at most once a day. Runs in the background through the shared
 * HTTP client, so it honours the proxy; nothing waits for it. A file is stored only after full
 * verification and takes effect at the next app start (see [EndpointCatalog]).
 *
 * A failed download or a discarded file changes nothing and is only reported to [debugLog].
 */
class EndpointConfigUpdater(
    private val http: HttpClient,
    private val store: EndpointConfigStore,
    private val verifier: EndpointFileVerifier = EndpointFileVerifier(),
    private val urls: List<String> = DEFAULT_URLS,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val debugLog: (String) -> Unit = {},
) {
    /** Never fails: storage or network trouble only means staying on what is already there. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun updateIfDue(): EndpointUpdateResult = try {
        update()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        debugLog("Server list update failed: ${failure::class.simpleName}")
        EndpointUpdateResult.FAILED
    }

    private suspend fun update(): EndpointUpdateResult {
        if (!store.autoUpdateEnabled()) return EndpointUpdateResult.DISABLED
        val now = nowSeconds()
        // A last check in the future means the clock was wrong then; it must not block updates.
        if (store.lastCheckSeconds()?.let { it <= now && now - it < CHECK_INTERVAL_SECONDS } == true) {
            return EndpointUpdateResult.NOT_DUE
        }
        // Recorded before the request and whatever its outcome: at most one attempt a day, always.
        store.saveLastCheckSeconds(now)

        // An expired stored file still counts here, or an old signed file could be replayed after it.
        val current = store.storedEnvelope()?.let { verifier.open(it) as? EndpointFileResult.Valid }?.file?.version ?: 0
        for (url in urls) {
            val envelope = download(url) ?: continue
            when (val result = check(envelope, current, now)) {
                is EndpointFileResult.Rejected -> {
                    debugLog("Discarded server list from mirror: ${result.reason}")
                }

                is EndpointFileResult.Valid -> {
                    if (result.file.version == current) return EndpointUpdateResult.UNCHANGED
                    store.saveEnvelope(envelope)
                    return EndpointUpdateResult.UPDATED
                }
            }
        }
        return EndpointUpdateResult.FAILED
    }

    /** A lower version is a replay. The same version is valid but there is nothing to do. */
    private fun check(envelope: String, currentVersion: Long, now: Long): EndpointFileResult {
        val result = verifier.open(envelope)
        if (result !is EndpointFileResult.Valid) return result
        return when {
            result.file.isExpired(now) -> EndpointFileResult.Rejected(EndpointRejection.EXPIRED)
            result.file.version < currentVersion -> EndpointFileResult.Rejected(EndpointRejection.STALE_VERSION)
            else -> result
        }
    }

    /** Reads at most one byte over the limit, so an oversized answer never fills the memory. */
    @Suppress("TooGenericExceptionCaught") // Any failure of a mirror just means trying the next one.
    private suspend fun download(url: String): String? = try {
        http.prepareGet(url) {
            timeout {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
            retry { noRetry() }
        }.execute { response ->
            response.bodyAsChannel().readRemaining(EndpointFileVerifier.MAX_ENVELOPE_BYTES + 1L).readByteArray().decodeToString()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        debugLog("Server list download failed: ${failure::class.simpleName}")
        null
    }

    companion object {
        /** Both serve the same file from the public repository; the second is only a fallback. */
        val DEFAULT_URLS = listOf(
            "https://raw.githubusercontent.com/altaraneu/watchwallet/main/config/endpoints.json",
            "https://cdn.jsdelivr.net/gh/altaraneu/watchwallet@main/config/endpoints.json",
        )

        const val CHECK_INTERVAL_SECONDS = 24 * 60 * 60L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 20_000L
    }
}
