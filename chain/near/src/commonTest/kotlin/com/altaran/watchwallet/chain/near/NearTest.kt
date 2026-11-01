package com.altaran.watchwallet.chain.near

import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NearTest {
    @Test
    fun accountIdValidation() {
        val strict = listOf(
            "example.near" to true,
            "app.sweat" to true,
            "relay.tg" to true,
            "98793cd91a3f870fb126f66285808c7e094afcfc4eda8a970f6648cdf0dbd6de" to true, // implicit
            "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed" to true, // ETH-implicit (lowercase)
            "aurora" to false, // valid top-level id, but only accepted by parseAny
            "Example.near" to false, // upper case
            "ex..near" to false, // empty part
            "a-.near" to false, // separator at the end of a part
            "x".repeat(60) + ".near" to false, // longer than 64
            "98793cd91a3f870fb126f66285808c7e094afcfc4eda8a970f6648cdf0dbd6d" to false, // 63 hex, no suffix
            ".near" to false,
        )
        strict.forEach { (input, valid) -> assertEquals(valid, NearAddresses.parse(input) != null, input) }
        assertNotNull(NearAddresses.parseAny("aurora"))
        assertEquals(null, NearAddresses.parseAny("a"), "too short")
        assertEquals(null, NearAddresses.parseAny("bad name"))
    }

    private val engine = MockEngine { request ->
        val body = request.body.toByteArray().decodeToString()
        val response = if ("example.near" in body) {
            """{"jsonrpc":"2.0","result":{"amount":"1500000000000000000000000","locked":"500000000000000000000000","code_hash":"11111111111111111111111111111111",
                "storage_usage":182,"storage_paid_at":0,"block_height":120000000,"block_hash":"abc"},"id":1}"""
        } else {
            """{"jsonrpc":"2.0","error":{"name":"HANDLER_ERROR","cause":{"info":{"requested_account_id":"nobody.near"},"name":"UNKNOWN_ACCOUNT"},
                "code":-32000,"message":"Server error","data":"account nobody.near does not exist while viewing"},"id":1}"""
        }
        respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val adapter = NearAdapter(createHttpClient(engine = engine), "https://near.test")

    @Test
    fun balanceIncludesLockedStake() = runTest { withContext(Dispatchers.Default) {
        val result = adapter.sync(assertNotNull(NearAddresses.parse("example.near")), emptySet())
        assertEquals("2", result.balances.single().amount.toPlainString())
        assertTrue(result.transactions.isEmpty())
    } }

    @Test
    fun unknownAccountHasNoActivity() = runTest { withContext(Dispatchers.Default) {
        assertFalse(adapter.probe(assertNotNull(NearAddresses.parse("nobody.near"))).hasActivity)
    } }
}
