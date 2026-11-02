package com.altaran.watchwallet.core.network

import io.ktor.client.call.body
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The real OkHttp engine against local sockets, because the client-level `followRedirects = false`
 * says nothing about the engine, which follows redirects on its own when configured to.
 */
class RedirectPolicyTest {
    private class LocalServer(private val response: () -> String) : AutoCloseable {
        private val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        val requests = CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true) {
                try {
                    while (true) socket.accept().use(::answer)
                } catch (_: SocketException) {
                    // Closed by the test.
                }
            }
        }

        private fun answer(client: Socket) {
            client.soTimeout = READ_TIMEOUT_MS
            val buffer = ByteArray(BUFFER_BYTES)
            val read = try {
                client.getInputStream().read(buffer)
            } catch (_: SocketTimeoutException) {
                -1
            }
            // Also counts a TLS handshake, which is how an https redirect target shows up here.
            requests += if (read > 0) buffer.decodeToString(0, read) else ""
            client.getOutputStream().write(response().encodeToByteArray())
        }

        override fun close() = socket.close()
    }

    private fun redirectTo(location: String) = "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    private fun assertNotFollowed(scheme: String) = runBlocking {
        LocalServer { "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}" }.use { second ->
            LocalServer { redirectTo("$scheme://127.0.0.1:${second.port}/address/$ADDRESS") }.use { first ->
                val client = createHttpClient(engine = createPlatformEngine(null))
                client.use {
                    assertFailsWith<RedirectResponseException> {
                        client.get("http://127.0.0.1:${first.port}/address/$ADDRESS") { header(KEY_HEADER, API_KEY) }.body<JsonElement>()
                    }
                }
                assertEquals(1, first.requests.size, "no retry of a redirect")
                assertTrue(ADDRESS in first.requests.single(), "the request reached the first host")
                assertEquals(emptyList(), second.requests.toList(), "nothing may reach the second host")
            }
        }
    }

    @Test
    fun aRedirectToAnotherHostIsNotFollowed() = assertNotFollowed("http")

    @Test
    fun aRedirectToAnotherSchemeIsNotFollowed() = assertNotFollowed("https")

    private companion object {
        const val ADDRESS = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        const val KEY_HEADER = "x-cg-demo-api-key"
        const val API_KEY = "test-key"
        const val BACKLOG = 8
        const val READ_TIMEOUT_MS = 2_000
        const val BUFFER_BYTES = 8_192
    }
}
