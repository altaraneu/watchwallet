package com.altaran.watchwallet.core.network

import com.altaran.watchwallet.core.common.CustomServerUrl
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Optional SOCKS5 proxy (for example Orbot on 127.0.0.1:9050) for all traffic. */
data class ProxyConfig(val host: String, val port: Int)

/** Shared JSON configuration: tolerant to unknown fields because public APIs evolve. */
val NetworkJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

/** Platform HTTP engine (OkHttp on Android/JVM, Darwin on iOS). */
expect fun createPlatformEngine(proxy: ProxyConfig?): HttpClientEngine

/**
 * Whether [createPlatformEngine] honours a [ProxyConfig]. When `false`, the UI must not offer the
 * proxy option, so nobody believes their traffic is routed through Tor when it is not.
 */
expect val proxySupported: Boolean

/** A request without TLS to a host outside the device or the local network. Never sent. */
class CleartextRefusedException : IllegalStateException("Refusing a request without TLS to a public host")

/**
 * Plain http is possible only for a custom server on `.onion`, localhost or the local network. The
 * platform cannot express private address ranges, so this check is what enforces it for every call.
 */
internal val CleartextGuard = createClientPlugin("CleartextGuard") {
    onRequest { request, _ ->
        val scheme = request.url.protocol.name
        if ((scheme == "http" || scheme == "ws") && !CustomServerUrl.isCleartextAllowedHost(request.url.host)) throw CleartextRefusedException()
    }
}

private const val CONNECT_TIMEOUT_MS = 8_000L

/** Deadline of one request or page. Paging through a long history has no overall limit here. */
private const val REQUEST_TIMEOUT_MS = 20_000L

/**
 * When to repeat a failed request. One retry with a short pause: connection errors and 5xx may be
 * momentary, a client error will not change, and a host that is down must fail fast so callers can
 * fail over. Timeouts already used up the deadline and are not repeated.
 */
object HttpRetryPolicy {
    const val MAX_RETRIES = 1
    private const val BACKOFF_MS = 500L
    private const val RATE_LIMIT_BACKOFF_MS = 2_000L
    private const val MAX_RETRY_AFTER_SECONDS = 10L
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val MILLIS_PER_SECOND = 1_000L
    private val SERVER_ERRORS = 500..599

    fun shouldRetry(status: Int, retryAfter: String?): Boolean = when (status) {
        // Waiting longer than the cap would stall a sync; the next sync tries again.
        HTTP_TOO_MANY_REQUESTS -> (retryAfter?.trim()?.toLongOrNull() ?: 0L) <= MAX_RETRY_AFTER_SECONDS

        in SERVER_ERRORS -> true

        else -> false
    }

    fun shouldRetry(cause: Throwable): Boolean = when (cause) {
        is kotlinx.coroutines.CancellationException,
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException,
        -> false

        else -> true
    }

    fun delayMillis(status: Int?, retryAfter: String?): Long {
        if (status != HTTP_TOO_MANY_REQUESTS) return BACKOFF_MS
        val seconds = retryAfter?.trim()?.toLongOrNull() ?: return RATE_LIMIT_BACKOFF_MS
        return seconds.coerceIn(0, MAX_RETRY_AFTER_SECONDS) * MILLIS_PER_SECOND
    }
}

/**
 * Creates the app's HTTP client. No cookies, no analytics headers, no custom user agent that
 * identifies the user. Redirects are not followed: an API moving elsewhere must never send an
 * address to a host that is not listed in `PRIVACY.md`; the request fails over instead.
 */
fun createHttpClient(
    proxy: ProxyConfig? = null,
    engine: HttpClientEngine = createPlatformEngine(proxy),
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient = HttpClient(engine) {
    expectSuccess = true
    followRedirects = false
    install(CleartextGuard)
    install(ContentNegotiation) {
        json(NetworkJson)
        // Some public APIs answer JSON with a text/plain content type.
        json(NetworkJson, ContentType.Text.Plain)
    }
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
    install(HttpRequestRetry) {
        maxRetries = HttpRetryPolicy.MAX_RETRIES
        retryIf { _, response -> HttpRetryPolicy.shouldRetry(response.status.value, response.headers[HttpHeaders.RetryAfter]) }
        retryOnExceptionIf { _, cause -> HttpRetryPolicy.shouldRetry(cause) }
        delayMillis(respectRetryAfterHeader = false) {
            HttpRetryPolicy.delayMillis(response?.status?.value, response?.headers?.get(HttpHeaders.RetryAfter))
        }
    }
    install(io.ktor.client.plugins.websocket.WebSockets) {
        pingIntervalMillis = 20_000
    }
    defaultRequest {
        headers.append(HttpHeaders.Accept, "application/json")
    }
    configure()
}
