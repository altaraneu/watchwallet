// Fixtures contain long real addresses and JSON payloads.
@file:Suppress("ktlint:standard:max-line-length")

package com.altaran.watchwallet.chain.algorand

import com.altaran.watchwallet.chain.common.Hex
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

class AlgorandTest {
    private val feeSink = "Y76M3MSY6DKBRHBL7C3NNDXGS5IIMQVQVUAB6MP4XEMMGVF2QWNPL226CA"
    private val zero = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAY5HFKQ"

    @Test
    fun sha512t256Vector() {
        assertEquals(
            "53048e2681941ef99b2e29b76b4c7dabe4c2d0c634fc6d46e0e2f13107e7af23",
            Hex.encode(AlgorandAddresses.sha512t256("abc".encodeToByteArray())),
        )
    }

    @Test
    fun addressValidation() {
        val cases = listOf(
            feeSink to true, // mainnet fee sink
            zero to true, // zero address
            AlgorandAddresses.encode(ByteArray(32) { it.toByte() }) to true,
            "Y76M3MSY6DKBRHBL7C3NNDXGS5IIMQVQVUAB6MP4XEMMGVF2QWNPL226CB" to false, // wrong checksum
            "Y76M3MSY6DKBRHBL7C3NNDXGS5IIMQVQVUAB6MP4XEMMGVF2QWNPL226" to false, // wrong length
            "y76m3msy6dkbrhbl7c3nndxgs5iimqvqvuab6mp4xemmgvf2qwnpl226ca" to false, // lower case
            "GA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJVSGZ" to false, // Stellar (56 chars)
        )
        cases.forEach { (input, valid) -> assertEquals(valid, AlgorandAddresses.parse(input) != null, input) }
    }

    private val ours = AlgorandAddresses.encode(ByteArray(32) { 7 })
    private val other = feeSink

    private val transactions = """{"current-round":40000000,"next-token":"tok","transactions":[
      {"id":"TX2","round-time":1717000000,"confirmed-round":39000000,"sender":"$ours","fee":1000,"tx-type":"axfer",
       "asset-transfer-transaction":{"amount":2500000,"asset-id":31566704,"close-amount":0,"receiver":"$other"}},
      {"id":"TX1","round-time":1716000000,"confirmed-round":38000000,"sender":"$other","fee":1000,"tx-type":"pay",
       "payment-transaction":{"amount":5000000,"close-amount":0,"receiver":"$ours"}},
      {"id":"TX0","round-time":1715000000,"confirmed-round":37000000,"sender":"$ours","fee":2000,"tx-type":"appl",
       "application-transaction":{"application-id":1},
       "inner-txns":[{"sender":"APPADDRESS","fee":0,"tx-type":"pay","payment-transaction":{"amount":700000,"close-amount":0,"receiver":"$ours"}}]}]}"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = when {
            path == "/v2/accounts/$ours" -> {
                """{"current-round":40000000,"account":{"address":"$ours","amount":12500000,"total-assets-opted-in":2,
                "assets":[{"amount":7500000,"asset-id":31566704,"is-frozen":false},{"amount":0,"asset-id":999,"is-frozen":false}]}}"""
            }

            path == "/v2/accounts/$ours/transactions" -> {
                transactions
            }

            path == "/v2/assets/31566704" -> {
                """{"asset":{"index":31566704,"params":{"creator":"X","decimals":6,"name":"USDC","unit-name":"USDC","total":18446744073709551615}}}"""
            }

            else -> {
                null
            }
        }
        if (body == null) {
            respond(
                """{"message":"no accounts found for address"}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        } else {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private val adapter = AlgorandAdapter(createHttpClient(engine = engine), "https://idx.test")

    @Test
    fun syncParsesPaymentsAssetsAndInnerTransactions() = runTest {
        withContext(Dispatchers.Default) {
            val result = adapter.sync(assertNotNull(AlgorandAddresses.parse(ours)), emptySet())
            assertEquals(listOf("ALGO", "USDC"), result.balances.map { it.asset.symbol })
            assertEquals("12.5", result.balances[0].amount.toPlainString())
            assertEquals("usd-coin", result.balances[1].asset.priceId)

            val byKey = result.transactions.associateBy { it.hash + "/" + it.asset.symbol }
            assertEquals(-1000L, byKey.getValue("TX2/ALGO").delta.longValue(), "fee of the asset transfer")
            assertEquals(-2_500_000L, byKey.getValue("TX2/USDC").delta.longValue())
            assertEquals(5_000_000L, byKey.getValue("TX1/ALGO").delta.longValue())
            assertEquals(698_000L, byKey.getValue("TX0/ALGO").delta.longValue(), "inner payment minus app-call fee")
        }
    }

    @Test
    fun unknownAccount() = runTest {
        withContext(Dispatchers.Default) {
            assertFalse(adapter.probe(assertNotNull(AlgorandAddresses.parse(feeSink))).hasActivity)
        }
    }
}
