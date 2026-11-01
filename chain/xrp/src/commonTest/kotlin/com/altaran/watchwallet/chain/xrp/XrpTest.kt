package com.altaran.watchwallet.chain.xrp

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XrpTest {
    private val ours = "rGWrZyQqhTp9Xu7G5Pkayo7bXjH4k4QYpf"
    private val other = "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh"
    private val issuer = "rvYAfWj5gh67oV6fW32ZzP3Aw4Eubs59B"

    @Test
    fun addressValidation() {
        val cases = listOf(
            ours to ours,
            other to other,
            "rrrrrrrrrrrrrrrrrrrrrhoLvTp" to "rrrrrrrrrrrrrrrrrrrrrhoLvTp", // ACCOUNT_ZERO
            "X7AcgcsBL6XDcUb289X4mJ8djcdyKaB5hJDWMArnXr61cqZ" to "r9cZA1mLK5R5Am25ArfXFmqgNwjZgnfk59", // XLS-5 spec vector
            "rGWrZyQqhTp9Xu7G5Pkayo7bXjH4k4QYpg" to null, // wrong checksum
            "rGWrZyQqhTp9Xu7G5Pkayo7bXjH4k4QY" to null, // wrong length
            "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA" to null, // Bitcoin alphabet / network
            "sEdTM1uX8pu2do5XvTnutH6HsouMaM2" to null, // family seed, not an address
        )
        cases.forEach { (input, expected) -> assertEquals(expected, XrpAddresses.parse(input)?.address, input) }
    }

    @Test
    fun xAddressWithTagAndTestnet() {
        val tagged = assertNotNull(XrpAddresses.toXAddress(ours, tag = 12345))
        assertTrue(tagged.startsWith("X"))
        assertEquals(ours, XrpAddresses.parse(tagged)?.address)
        assertEquals("X7AcgcsBL6XDcUb289X4mJ8djcdyKaB5hJDWMArnXr61cqZ", XrpAddresses.toXAddress("r9cZA1mLK5R5Am25ArfXFmqgNwjZgnfk59"))
        // Testnet X-addresses start with T and are rejected.
        assertNull(XrpAddresses.parse("T719a5UwUCnEs54UsxG9CJYYDhwmFCqkr7wxCcNcfZ6p5GZ"))
    }

    @Test
    fun hexCurrencyCodes() {
        assertEquals("RLUSD", currencyCode("524C555344000000000000000000000000000000"))
        assertEquals("USD", currencyCode("USD"))
    }

    private val accountTx = """{"result":{"account":"$ours","ledger_index_max":90000000,"ledger_index_min":32570,"limit":200,"status":"success","validated":true,
      "transactions":[
        {"meta":{"AffectedNodes":[
           {"ModifiedNode":{"FinalFields":{"Account":"$ours","Balance":"74999988","Flags":0,"OwnerCount":1,"Sequence":5},"LedgerEntryType":"AccountRoot",
             "PreviousFields":{"Balance":"75000000","Sequence":4}}},
           {"ModifiedNode":{"FinalFields":{"Balance":{"currency":"USD","issuer":"rrrrrrrrrrrrrrrrrrrrBZbvji","value":"-40"},"Flags":131072,
             "HighLimit":{"currency":"USD","issuer":"$ours","value":"1000"},"LowLimit":{"currency":"USD","issuer":"$issuer","value":"0"}},
             "LedgerEntryType":"RippleState","PreviousFields":{"Balance":{"currency":"USD","issuer":"rrrrrrrrrrrrrrrrrrrrBZbvji","value":"-50"}}}}],
           "TransactionIndex":3,"TransactionResult":"tesSUCCESS"},
         "tx":{"Account":"$ours","Amount":{"currency":"USD","issuer":"$issuer","value":"10"},"Destination":"$other","Fee":"12","Sequence":4,
           "TransactionType":"Payment","date":770000000,"hash":"HASH2"},"validated":true},
        {"meta":{"AffectedNodes":[
           {"ModifiedNode":{"FinalFields":{"Account":"$other","Balance":"999","Flags":0,"OwnerCount":0,"Sequence":9},"LedgerEntryType":"AccountRoot","PreviousFields":{"Balance":"75001011"}}},
           {"CreatedNode":{"LedgerEntryType":"AccountRoot","NewFields":{"Account":"$ours","Balance":"75000000","Sequence":1}}}],
           "TransactionResult":"tesSUCCESS"},
         "tx":{"Account":"$other","Amount":"75000000","Destination":"$ours","Fee":"12","TransactionType":"Payment","date":760000000,"hash":"HASH1"},"validated":true}]}}"""

    private val engine = MockEngine { request ->
        val body = request.body.toByteArray().decodeToString()
        val response = when {
            "account_info" in body -> """{"result":{"account_data":{"Account":"$ours","Balance":"74999988","Flags":0,"LedgerEntryType":"AccountRoot","OwnerCount":1,"Sequence":5},
                "ledger_index":90000000,"status":"success","validated":true}}"""
            "account_lines" in body -> """{"result":{"account":"$ours","lines":[{"account":"$issuer","balance":"40","currency":"USD","limit":"1000","limit_peer":"0","quality_in":0,"quality_out":0},
                {"account":"$other","balance":"0","currency":"EUR","limit":"10","limit_peer":"0"}],"status":"success","validated":true}}"""
            "account_tx" in body -> accountTx
            "server_state" in body -> """{"result":{"state":{"validated_ledger":{"base_fee":10,"close_time":770000000,"hash":"X","reserve_base":1000000,"reserve_inc":200000,"seq":90000000}},"status":"success"}}"""
            else -> error("Unexpected $body")
        }
        respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val adapter = XrpAdapter(createHttpClient(engine = engine), listOf("https://xrpl.test"))
    private val target = assertNotNull(XrpAddresses.parse(ours))

    @Test
    fun syncParsesBalancesAndMetadataDeltas() = runTest { withContext(Dispatchers.Default) {
        val result = adapter.sync(target, emptySet())
        assertEquals(listOf("XRP", "USD"), result.balances.map { it.asset.symbol }, "zero trust lines skipped")
        assertEquals("74.999988", result.balances[0].amount.toPlainString())
        assertEquals("40", result.balances[1].amount.toPlainString())

        val tx2 = result.transactions.filter { it.hash == "HASH2" }.associateBy { it.asset.symbol }
        assertEquals(-12L, tx2.getValue("XRP").delta.longValue(), "fee only")
        assertEquals(12L, tx2.getValue("XRP").fee?.longValue())
        assertEquals("-10", com.altaran.watchwallet.core.model.TokenAmount(tx2.getValue("USD").delta, 15).toPlainString())
        val tx1 = result.transactions.single { it.hash == "HASH1" }
        assertEquals(75_000_000L, tx1.delta.longValue(), "account creation")
        assertNull(tx1.fee)
    } }

    @Test
    fun reserveAndUnfundedAccount() = runTest { withContext(Dispatchers.Default) {
        assertEquals("1.2", adapter.lockedReserve(target).toPlainString(), "1 XRP base + 1 owned object × 0.2")
        val unfunded = MockEngine {
            respond(
                """{"result":{"account":"$other","error":"actNotFound","error_code":19,"error_message":"Account not found.","status":"error","validated":true}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val probe = XrpAdapter(createHttpClient(engine = unfunded), listOf("https://xrpl.test")).probe(target)
        assertFalse(probe.hasActivity)
    } }
}
