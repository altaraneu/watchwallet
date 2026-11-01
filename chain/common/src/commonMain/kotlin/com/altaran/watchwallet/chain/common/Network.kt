package com.altaran.watchwallet.chain.common

import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.core.network.Throttle
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Runs a data-source call and wraps every unexpected failure in [ChainDataException], as the
 * [com.altaran.watchwallet.chain.api.ChainAdapter] contract requires.
 */
suspend fun <T> chainCall(source: String, block: suspend () -> T): T = try {
    block()
} catch (e: ChainDataException) {
    throw e
} catch (e: CancellationException) {
    throw e
} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
    throw ChainDataException("$source data source failed: ${e.message}", e)
}

/** `true` when the exception is an HTTP response with the given status (for example 404). */
fun Throwable.isHttpStatus(status: Int): Boolean = this is ResponseException && response.status.value == status

/** Runs [block] and maps HTTP 404 (unknown/unfunded account on many APIs) to `null`. */
suspend fun <T> notFoundAsNull(block: suspend () -> T): T? = try {
    block()
} catch (e: ResponseException) {
    if (e.response.status.value == 404) null else throw e
}

/**
 * Retries [block] after HTTP 429 with exponential backoff (1 s, 2 s, 4 s…). This comes on top of
 * the HTTP client's own retries, for APIs with strict per-IP limits (Solana, TronGrid).
 */
suspend fun <T> withRateLimitBackoff(retries: Int, block: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: ResponseException) {
            if (e.response.status.value != 429 || attempt >= retries) throw e
            delay(BACKOFF_BASE_MS shl attempt)
            attempt++
        }
    }
}

private const val BACKOFF_BASE_MS = 1_000L

/** Minimal JSON-RPC 2.0 client: positional or named params, error object → [ChainDataException]. */
class JsonRpcClient(
    private val http: HttpClient,
    private val urls: List<String>,
    private val throttle: Throttle,
    /** Extra retries with exponential backoff when the node answers HTTP 429. */
    private val rateLimitRetries: Int = 0,
) {
    init {
        require(urls.isNotEmpty())
    }

    /** Calls [method]; tries the fallback URLs on transport failures (not on RPC errors). */
    suspend fun call(method: String, params: JsonElement = JsonArray(emptyList())): JsonElement {
        var last: Exception? = null
        for (url in urls) {
            try {
                return withRateLimitBackoff(rateLimitRetries) { callOnce(url, method, params) }
            } catch (e: RpcErrorException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                last = e
            }
        }
        throw last ?: ChainDataException("No RPC endpoint")
    }

    private suspend fun callOnce(url: String, method: String, params: JsonElement): JsonElement {
        val response: JsonObject = throttle.run {
            http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", method)
                        put("params", params)
                    },
                )
            }.body()
        }
        val error = response["error"]
        if (error != null && error !is JsonNull) throw RpcErrorException(method, error)
        return response["result"] ?: JsonNull
    }
}

/** A JSON-RPC error object returned by the node. */
class RpcErrorException(method: String, val error: JsonElement) : Exception("RPC $method failed: $error") {
    val code: Long? get() = ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.longOrNull
    val text: String get() = error.toString()
}

// ---------------------------------------------------------------- JSON navigation helpers

fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

fun JsonElement?.arr(key: String): List<JsonElement> = ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty()

fun JsonElement?.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

fun JsonElement?.long(key: String): Long? = str(key)?.toLongOrNull()

fun JsonElement?.at(vararg path: String): JsonElement? {
    var current: JsonElement? = this
    for (key in path) current = (current as? JsonObject)?.get(key)
    return current
}

val JsonElement?.asList: List<JsonElement> get() = (this as? JsonArray).orEmpty()

val JsonElement?.content: String? get() = (this as? JsonPrimitive)?.contentOrNull
