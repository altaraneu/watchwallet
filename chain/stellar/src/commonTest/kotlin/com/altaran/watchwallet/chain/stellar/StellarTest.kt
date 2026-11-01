package com.altaran.watchwallet.chain.stellar

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
import kotlin.test.assertTrue

class StellarTest {
    private val ours = "GA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJVSGZ"
    private val other = "GAAZI4TCR3TY5OJHCTJC2A4QSY6CJWJH5IAJTGKIN2ER7LBNVKOCCWN7"
    private val usdcIssuer = "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"

    @Test
    fun addressValidation() {
        val cases = listOf(
            ours to ours,
            other to other,
            usdcIssuer to usdcIssuer,
            "GA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJVSGA" to null, // wrong checksum
            "GA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJVS" to null, // wrong length
            "ga7qynf7sowq3glr2bgmzehxavirza4kvwltjjfc7mgxua74p7ujvsgz" to null, // lower case is not strkey
            "SA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJVSGZ" to null, // secret-seed version byte
            "rGWrZyQqhTp9Xu7G5Pkayo7bXjH4k4QYpf" to null, // XRP
        )
        cases.forEach { (input, expected) -> assertEquals(expected, StellarAddresses.parse(input)?.address, input) }
    }

    @Test
    fun muxedAddressMapsToAccount() {
        val muxed = assertNotNull(StellarAddresses.toMuxed(ours, 1234567890123L))
        assertEquals(69, muxed.length)
        assertTrue(muxed.startsWith("M"))
        assertEquals(ours, StellarAddresses.parse(muxed)?.address)
        val broken = muxed.dropLast(1) + if (muxed.last() == 'A') 'B' else 'A'
        assertEquals(null, StellarAddresses.parse(broken))
    }

    private val account = """{"id":"$ours","account_id":"$ours","sequence":"123","subentry_count":2,"num_sponsoring":0,"num_sponsored":0,
      "balances":[
        {"balance":"250.0000000","limit":"922337203685.4775807","asset_type":"credit_alphanum4","asset_code":"USDC","asset_issuer":"$usdcIssuer"},
        {"balance":"0.0000000","limit":"1000.0000000","asset_type":"credit_alphanum12","asset_code":"SPAMCOIN","asset_issuer":"$other"},
        {"balance":"5.0000000","liquidity_pool_id":"abc","asset_type":"liquidity_pool_shares"},
        {"balance":"100.5000000","buying_liabilities":"0.0000000","selling_liabilities":"0.0000000","asset_type":"native"}]}"""

    private val payments = """{"_links":{},"_embedded":{"records":[
      {"id":"3","paging_token":"3","transaction_successful":true,"source_account":"$ours","type":"payment","created_at":"2024-05-02T10:00:00Z",
       "transaction_hash":"h3","asset_type":"native","from":"$ours","to":"$other","amount":"10.0000000"},
      {"id":"2","paging_token":"2","transaction_successful":true,"source_account":"$other","type":"payment","created_at":"2024-05-01T10:00:00Z",
       "transaction_hash":"h2","asset_type":"credit_alphanum4","asset_code":"USDC","asset_issuer":"$usdcIssuer","from":"$other","to":"$ours","amount":"250.0000000"},
      {"id":"1","paging_token":"1","transaction_successful":true,"source_account":"$other","type":"create_account","created_at":"2024-04-01T10:00:00Z",
       "transaction_hash":"h1","starting_balance":"110.5000100","funder":"$other","account":"$ours"}]}}"""

    private val transactions = """{"_links":{},"_embedded":{"records":[
      {"id":"h3","paging_token":"3","successful":true,"hash":"h3","created_at":"2024-05-02T10:00:00Z","source_account":"$ours","fee_account":"$ours","fee_charged":"100"},
      {"id":"h4","paging_token":"2","successful":true,"hash":"h4","created_at":"2024-05-01T12:00:00Z","source_account":"$ours","fee_account":"$ours","fee_charged":"100"}]}}"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val response = when {
            path.endsWith("/payments") -> payments
            path.endsWith("/transactions") -> transactions
            path == "/accounts/$ours" -> account
            else -> null
        }
        if (response == null) {
            respond(
                """{"status":404}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        } else {
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private val adapter = StellarAdapter(createHttpClient(engine = engine), "https://horizon.test")

    @Test
    fun syncReturnsBalancesAndDeltas() = runTest {
        withContext(Dispatchers.Default) {
            val result = adapter.sync(assertNotNull(StellarAddresses.parse(ours)), emptySet())
            assertEquals(listOf("XLM", "USDC"), result.balances.map { it.asset.symbol })
            assertEquals("100.5", result.balances[0].amount.toPlainString())
            assertEquals("usd-coin", result.balances[1].asset.priceId)

            val byKey = result.transactions.associateBy { it.hash + "/" + it.asset.symbol }
            assertEquals(-100_000_100L, byKey.getValue("h3/XLM").delta.longValue(), "10 XLM + 100 stroops fee")
            assertEquals(100L, byKey.getValue("h3/XLM").fee?.longValue())
            assertEquals(-100L, byKey.getValue("h4/XLM").delta.longValue(), "fee-only transaction")
            assertEquals(2_500_000_000L, byKey.getValue("h2/USDC").delta.longValue())
            assertEquals(1_105_000_100L, byKey.getValue("h1/XLM").delta.longValue())
        }
    }

    @Test
    fun reserveAndUnfundedAccount() = runTest {
        withContext(Dispatchers.Default) {
            assertEquals(
                "2",
                adapter.lockedReserve(assertNotNull(StellarAddresses.parse(ours))).toPlainString(),
                "(2 + 2) × 0.5 XLM",
            )
            val probe = adapter.probe(assertNotNull(StellarAddresses.parse(other)))
            assertFalse(probe.hasActivity)
        }
    }
}
