package com.altaran.watchwallet.chain.tezos

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

class TezosTest {
    private val ours = "tz1VSUr8wwNhLAzempoch5d6hLRiTh8Cjcjb"
    private val other = "tz2BFTyPeYRzxd5aiBchbXN3WCZhx7BqbMBq"
    private val tzbtc = "KT1PWx2mnDueood7fEmfbBDKx1D9BAnnXitn"

    @Test
    fun addressValidation() {
        val cases = listOf(
            ours to true,
            other to true,
            "tz3WXYtyDUNL91qfiCJtVUX746QpNv5i5ve5" to true,
            tzbtc to true,
            "KT1XnTn74bUtxHfDtBmm2bGZAQfhPbvKWR8o" to true,
            "tz1VSUr8wwNhLAzempoch5d6hLRiTh8Cjcja" to false, // wrong checksum
            "tz1VSUr8wwNhLAzempoch5d6hLRiTh8Cjcj" to false, // wrong length
            "tz9VSUr8wwNhLAzempoch5d6hLRiTh8Cjcjb" to false, // unknown prefix
            "edpkuBknW28nW72KG6RoHtYW7p12T6GKc7nAbwYX5m8Wd9sDVC9yav" to false, // public key, not an address
            "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA" to false, // Bitcoin
        )
        cases.forEach { (input, valid) -> assertEquals(valid, TezosAddresses.parse(input) != null, input) }
    }

    private val operations = """[
      {"type":"transaction","id":300,"level":5000000,"timestamp":"2024-05-02T10:00:00Z","hash":"opB","counter":11,"sender":{"address":"$ours"},
       "gasLimit":1000,"gasUsed":900,"storageLimit":0,"storageUsed":0,"bakerFee":400,"storageFee":0,"allocationFee":0,
       "target":{"address":"$other"},"amount":2000000,"status":"applied","hasInternals":false},
      {"type":"reveal","id":299,"level":5000000,"timestamp":"2024-05-02T10:00:00Z","hash":"opB","sender":{"address":"$ours"},"bakerFee":300,"status":"applied"},
      {"type":"transaction","id":200,"level":4900000,"timestamp":"2024-05-01T10:00:00Z","hash":"opA","sender":{"address":"$other"},
       "bakerFee":500,"storageFee":0,"allocationFee":64250,"target":{"address":"$ours"},"amount":10000000,"status":"applied"},
      {"type":"transaction","id":100,"level":4800000,"timestamp":"2024-04-01T10:00:00Z","hash":"opFailed","sender":{"address":"$ours"},
       "bakerFee":450,"storageFee":0,"allocationFee":0,"target":{"address":"$other"},"amount":5000000,"status":"backtracked"}]"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = when {
            path == "/v1/accounts/$ours" -> """{"type":"user","address":"$ours","alias":null,"counter":11,"balance":7599250,"numTransactions":3,"delegate":null}"""
            path == "/v1/accounts/$other" -> """{"type":"empty","address":"$other","counter":0}"""
            path == "/v1/accounts/$ours/operations" -> operations
            path == "/v1/tokens/balances" -> """[
                {"id":1,"account":{"address":"$ours"},"token":{"id":2,"contract":{"address":"$tzbtc"},"tokenId":"0","standard":"fa1.2",
                 "metadata":{"name":"tzBTC","symbol":"tzBTC","decimals":"8"}},"balance":"150000000"},
                {"id":3,"account":{"address":"$ours"},"token":{"id":4,"contract":{"address":"KT1XnTn74bUtxHfDtBmm2bGZAQfhPbvKWR8o"},"tokenId":"42","standard":"fa2",
                 "metadata":{"name":"Some NFT"}},"balance":"1"}]"""
            else -> error("Unexpected $path")
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val adapter = TezosAdapter(createHttpClient(engine = engine), "https://tzkt.test/v1")

    @Test
    fun syncGroupsOperationsByHash() = runTest { withContext(Dispatchers.Default) {
        val result = adapter.sync(assertNotNull(TezosAddresses.parse(ours)), emptySet())
        assertEquals(listOf("XTZ", "tzBTC"), result.balances.map { it.asset.symbol }, "NFT without decimals skipped")
        assertEquals("7.59925", result.balances[0].amount.toPlainString())
        assertEquals("1.5", result.balances[1].amount.toPlainString())

        val byHash = result.transactions.associateBy { it.hash }
        assertEquals(-2_000_700L, byHash.getValue("opB").delta.longValue(), "amount + transaction fee + reveal fee")
        assertEquals(700L, byHash.getValue("opB").fee?.longValue())
        assertEquals(10_000_000L, byHash.getValue("opA").delta.longValue(), "allocation fee is paid by the sender")
        assertEquals(-450L, byHash.getValue("opFailed").delta.longValue(), "failed operation costs only the fee")
    } }

    @Test
    fun emptyAccount() = runTest { withContext(Dispatchers.Default) {
        assertFalse(adapter.probe(assertNotNull(TezosAddresses.parse(other))).hasActivity)
    } }
}
