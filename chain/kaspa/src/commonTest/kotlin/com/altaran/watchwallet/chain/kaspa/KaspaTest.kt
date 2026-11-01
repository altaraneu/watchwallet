package com.altaran.watchwallet.chain.kaspa

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KaspaTest {
    private val schnorr = "kaspa:qrelgny7sr3vahq69yykxx36m65gvmhryxrlwngfzgu8xkdslum2yxjp3ap8m"
    private val devFund = "kaspa:precqv0krj3r6uyyfa36ga7s0u9jct0v4wg8ctsfde2gkrsgwgw8jgxfzfc98"

    @Test
    fun addressValidation() {
        val ecdsa = KaspaAddresses.encode(1, byteArrayOf(0x02) + ByteArray(32) { 9 })
        val cases = listOf(
            schnorr to schnorr,
            devFund to devFund, // P2SH (version 8)
            schnorr.removePrefix("kaspa:") to schnorr, // prefix omitted
            schnorr.uppercase() to schnorr,
            ecdsa to ecdsa,
            "kaspa:qrelgny7sr3vahq69yykxx36m65gvmhryxrlwngfzgu8xkdslum2yxjp3ap8n" to null, // wrong checksum
            "kaspa:qrelgny7sr3vahq69yykxx36m65gvmhryxrlwngfzgu8xkdslum2yxjp3a" to null, // wrong length
            "kaspatest:qrelgny7sr3vahq69yykxx36m65gvmhryxrlwngfzgu8xkdslum2yxjp3ap8m" to null, // testnet
            "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a" to null, // other CashAddr chain
            KaspaAddresses.encode(0, ByteArray(20)) to null, // wrong payload size for version 0
        )
        cases.forEach { (input, expected) -> assertEquals(expected, KaspaAddresses.parse(input)?.address, input) }
    }

    private val other = "kaspa:qypyqvcwa4sf6yzs4kvlhd9wsqnn3yfxkqq0uvwrjcd9fx0fjgamlzc0ytk5ag"

    private val transactions = """[
      {"subnetwork_id":"0000000000000000000000000000000000000000","transaction_id":"tx2","hash":"h2","mass":"2036","block_hash":["b2"],"block_time":1717000000000,
       "is_accepted":true,"accepting_block_hash":"a2","accepting_block_blue_score":90000000,
       "inputs":[{"transaction_id":"tx2","index":0,"previous_outpoint_hash":"tx1","previous_outpoint_index":"0","previous_outpoint_address":"$schnorr","previous_outpoint_amount":1000000000}],
       "outputs":[{"transaction_id":"tx2","index":0,"amount":300000000,"script_public_key_address":"$other"},
                  {"transaction_id":"tx2","index":1,"amount":699990000,"script_public_key_address":"$schnorr"}]},
      {"transaction_id":"tx1","block_time":1716000000000,"is_accepted":true,
       "inputs":[{"previous_outpoint_address":"$other","previous_outpoint_amount":5000000000}],
       "outputs":[{"amount":1000000000,"script_public_key_address":"$schnorr"},{"amount":3999990000,"script_public_key_address":"$other"}]}]"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath.replace("%3A", ":")
        val body = when {
            path.endsWith("/balance") -> """{"address":"$schnorr","balance":699990000}"""
            path.endsWith("/transactions-count") -> """{"total":2,"limit_exceeded":false}"""
            path.endsWith("/full-transactions") -> transactions
            else -> error("Unexpected $path")
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val adapter = KaspaAdapter(createHttpClient(engine = engine), "https://kaspa.test")

    @Test
    fun syncComputesUtxoDeltas() = runTest {
        withContext(Dispatchers.Default) {
            val result = adapter.sync(assertNotNull(KaspaAddresses.parse(schnorr)), emptySet())
            assertEquals("6.9999", result.balances.single().amount.toPlainString())
            val byHash = result.transactions.associateBy { it.hash }
            assertEquals(-300_010_000L, byHash.getValue("tx2").delta.longValue(), "3 KAS + 0.0001 KAS fee")
            assertEquals(10_000L, byHash.getValue("tx2").fee?.longValue())
            assertEquals(1_000_000_000L, byHash.getValue("tx1").delta.longValue())
        }
    }

    @Test
    fun probeUsesTransactionCount() = runTest {
        withContext(Dispatchers.Default) {
            val probe = adapter.probe(assertNotNull(KaspaAddresses.parse(schnorr)))
            assertTrue(probe.hasActivity)
            assertEquals(2, probe.transactionCount)
        }
    }
}
