package com.altaran.watchwallet.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CleartextGuardTest {
    private var sent = 0
    private val client = createHttpClient(
        engine = MockEngine {
            sent++
            respondOk()
        },
    )

    @Test
    fun plainHttpToAPublicHostNeverLeaves() = runTest {
        assertFailsWith<CleartextRefusedException> { client.get("http://mempool.space/api") }
        assertEquals(0, sent)
    }

    @Test
    fun plainHttpToALocalServerAndHttpsAreSent() = runTest {
        client.get("http://192.168.1.10:3006/api")
        client.get("http://abcdefghijklmnop.onion/api")
        client.get("https://mempool.space/api")
        assertEquals(3, sent)
    }
}
