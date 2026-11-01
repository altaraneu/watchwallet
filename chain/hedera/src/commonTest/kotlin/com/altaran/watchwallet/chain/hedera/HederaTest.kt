package com.altaran.watchwallet.chain.hedera

import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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

class HederaTest {
    @Test
    fun accountIdValidation() {
        val cases = listOf(
            "0.0.98" to "0.0.98",
            "0.0.2" to "0.0.2",
            "0.0.123-vfmkw" to "0.0.123", // HIP-15 example checksum
            "0.0.98-oljor" to "0.0.98",
            "0.0.123-vfmkx" to null, // wrong checksum
            "0.0" to null, // wrong length (missing part)
            "0.0.1.2" to null,
            "0.0.-1" to null,
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed" to null, // EVM alias not supported here
        )
        cases.forEach { (input, expected) -> assertEquals(expected, HederaAddresses.parse(input)?.address, input) }
        assertEquals("vfmkw", HederaAddresses.checksum("0.0.123"))
    }

    private val ours = "0.0.1234"

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = when {
            path == "/api/v1/accounts/$ours" ->
                """{"account":"$ours","balance":{"balance":250000000,"timestamp":"1717000000.000000000",
                   "tokens":[{"token_id":"0.0.456858","balance":5000000},{"token_id":"0.0.999","balance":1}]},"deleted":false,"links":{"next":null}}"""
            path == "/api/v1/tokens/0.0.456858" -> """{"token_id":"0.0.456858","symbol":"USDC","name":"USD Coin","decimals":"6","type":"FUNGIBLE_COMMON"}"""
            path == "/api/v1/tokens/0.0.999" -> """{"token_id":"0.0.999","symbol":"NFT","name":"Art","decimals":"0","type":"NON_FUNGIBLE_UNIQUE"}"""
            path == "/api/v1/transactions" && request.url.parameters["timestamp"] == null ->
                """{"transactions":[
                   {"transaction_id":"0.0.1234-1717000000-000000001","consensus_timestamp":"1717000001.123456789","charged_tx_fee":83000,"result":"SUCCESS","name":"CRYPTOTRANSFER","nonce":0,
                    "transfers":[{"account":"0.0.3","amount":4000},{"account":"0.0.98","amount":79000},{"account":"$ours","amount":-100083000},{"account":"0.0.5555","amount":100000000}],
                    "token_transfers":[]}],
                   "links":{"next":"/api/v1/transactions?account.id=$ours&order=desc&limit=100&timestamp=lt:1717000001.123456789"}}"""
            path == "/api/v1/transactions" ->
                """{"transactions":[
                   {"transaction_id":"0.0.5555-1716000000-000000001","consensus_timestamp":"1716000001.000000000","charged_tx_fee":90000,"result":"SUCCESS","name":"CRYPTOTRANSFER","nonce":0,
                    "transfers":[{"account":"0.0.5555","amount":-90000},{"account":"0.0.98","amount":90000}],
                    "token_transfers":[{"token_id":"0.0.456858","account":"0.0.5555","amount":-5000000},{"token_id":"0.0.456858","account":"$ours","amount":5000000}]}],
                   "links":{"next":null}}"""
            else -> null
        }
        if (body == null) {
            respond("""{"_status":{"messages":[{"message":"Not found"}]}}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private val adapter = HederaAdapter(createHttpClient(engine = engine), "https://mirror.test/api/v1")

    @Test
    fun syncFollowsNextLinks() = runTest { withContext(Dispatchers.Default) {
        val result = adapter.sync(assertNotNull(HederaAddresses.parse(ours)), emptySet())
        assertEquals(listOf("HBAR", "USDC"), result.balances.map { it.asset.symbol }, "NFT skipped")
        assertEquals("2.5", result.balances[0].amount.toPlainString())

        val byKey = result.transactions.associateBy { it.hash + "/" + it.asset.symbol }
        val send = byKey.getValue("0.0.1234-1717000000-000000001/HBAR")
        assertEquals(-100_083_000L, send.delta.longValue(), "1 HBAR + fee")
        assertEquals(83_000L, send.fee?.longValue())
        assertEquals(5_000_000L, byKey.getValue("0.0.5555-1716000000-000000001/USDC").delta.longValue())
        assertFalse(byKey.containsKey("0.0.5555-1716000000-000000001/HBAR"), "no HBAR change for us")
    } }

    @Test
    fun unknownAccount() = runTest { withContext(Dispatchers.Default) {
        assertFalse(adapter.probe(assertNotNull(HederaAddresses.parse("0.0.2"))).hasActivity)
    } }
}
