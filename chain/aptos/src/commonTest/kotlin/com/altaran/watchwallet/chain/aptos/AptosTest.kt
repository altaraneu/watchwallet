package com.altaran.watchwallet.chain.aptos

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

class AptosTest {
    private val ours = "0x190d44266241744264b964a37b8f09863167a12d3e70cda39376cfb4e3561e12"
    private val other = "0x0000000000000000000000000000000000000000000000000000000000000abc"

    @Test
    fun addressValidation() {
        val cases = listOf(
            ours to ours,
            "0x1" to "0x0000000000000000000000000000000000000000000000000000000000000001",
            "0xABC" to other,
            "0x" to null, // empty
            "0x190d44266241744264b964a37b8f09863167a12d3e70cda39376cfb4e3561e120" to null, // 65 hex
            "190d44266241744264b964a37b8f09863167a12d3e70cda39376cfb4e3561e12" to null, // no 0x prefix
            "0xzz" to null, // not hex
            "aptos.apt" to null, // ANS names are not resolved
        )
        cases.forEach { (input, expected) -> assertEquals(expected, AptosAddresses.parse(input)?.address, input) }
    }

    private val transactions = """[
      {"version":"100","hash":"0xh1","sender":"$ours","sequence_number":"0","success":true,"gas_used":"10","gas_unit_price":"100","timestamp":"1710000000000000",
       "payload":{"function":"0x1::aptos_account::transfer","type_arguments":[],"arguments":["$other","150000000"],"type":"entry_function_payload"},"events":[]},
      {"version":"200","hash":"0xh2","sender":"$ours","sequence_number":"1","success":true,"gas_used":"20","gas_unit_price":"100","timestamp":"1711000000000000",
       "payload":{"function":"0x1::coin::transfer","type_arguments":["0xf22::asset::USDT"],"arguments":["$other","5000"],"type":"entry_function_payload"},"events":[]}]"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = when {
            path.endsWith("/view") -> {
                assertTrue("0x1::coin::balance" in request.body.toByteArray().decodeToString())
                """["250000000"]"""
            }
            path.endsWith("/transactions") -> transactions
            path.endsWith("/accounts/$ours") -> """{"sequence_number":"2","authentication_key":"$ours"}"""
            else -> null
        }
        if (body == null) {
            respond("""{"message":"Account not found","error_code":"account_not_found"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private val adapter = AptosAdapter(createHttpClient(engine = engine), "https://aptos.test/v1")

    @Test
    fun syncReportsBalanceAndSentTransactions() = runTest { withContext(Dispatchers.Default) {
        val result = adapter.sync(assertNotNull(AptosAddresses.parse(ours)), emptySet())
        assertEquals("2.5", result.balances.single().amount.toPlainString())
        val byHash = result.transactions.associateBy { it.hash }
        assertEquals(-150_001_000L, byHash.getValue("0xh1").delta.longValue(), "1.5 APT + 1000 octas gas")
        assertEquals(-2_000L, byHash.getValue("0xh2").delta.longValue(), "non-APT coin transfer: gas only")
        assertEquals(2_000L, byHash.getValue("0xh2").fee?.longValue())
    } }

    @Test
    fun unknownAccount() = runTest { withContext(Dispatchers.Default) {
        assertFalse(adapter.probe(assertNotNull(AptosAddresses.parse(other))).hasActivity)
    } }
}
