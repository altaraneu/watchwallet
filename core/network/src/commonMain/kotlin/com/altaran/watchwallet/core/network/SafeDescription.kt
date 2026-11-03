package com.altaran.watchwallet.core.network

import io.ktor.client.plugins.ResponseException

/**
 * All that may be kept of a failure: its type and, for an HTTP answer, the status. The message of an
 * HTTP client's exception holds the URL, and so the address that URL asks about; a parser's can hold
 * part of the answer. For the same reason a wrapped exception is not kept as a cause.
 */
fun Throwable.safeDescription(): String {
    val type = this::class.simpleName ?: "Exception"
    return if (this is ResponseException) "$type HTTP ${response.status.value}" else type
}
