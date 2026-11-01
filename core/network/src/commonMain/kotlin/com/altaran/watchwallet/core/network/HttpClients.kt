package com.altaran.watchwallet.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
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

private const val CONNECT_TIMEOUT_MS = 15_000L
private const val REQUEST_TIMEOUT_MS = 45_000L
private const val MAX_RETRIES = 3

/**
 * Creates the app's HTTP client. No cookies, no analytics headers, no custom user agent that
 * identifies the user. Retries transient failures and HTTP 429 with exponential backoff.
 */
fun createHttpClient(
    proxy: ProxyConfig? = null,
    engine: HttpClientEngine = createPlatformEngine(proxy),
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient = HttpClient(engine) {
    expectSuccess = true
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
        maxRetries = MAX_RETRIES
        retryIf { _, response -> response.status.value == 429 || response.status.value in 500..599 }
        retryOnExceptionIf { _, cause -> cause !is kotlinx.coroutines.CancellationException }
        exponentialDelay(base = 2.0, maxDelayMs = 20_000, randomizationMs = 1_000)
    }
    install(io.ktor.client.plugins.websocket.WebSockets) {
        pingIntervalMillis = 20_000
    }
    defaultRequest {
        headers.append(HttpHeaders.Accept, "application/json")
    }
    configure()
}
