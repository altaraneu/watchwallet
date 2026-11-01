package com.altaran.watchwallet.chain.tron

import com.altaran.watchwallet.chain.common.Base58Checks
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TronTest {
    private val ours = "TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7"
    private val other = "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb"
    private fun hex(address: String) = Hex.encode(assertNotNull(Base58Checks.decode(address)))

    @Test
    fun addressValidation() {
        val cases = listOf(
            TronChain.USDT to true,
            ours to true,
            other to true,
            "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6u" to false, // wrong checksum
            "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6" to false, // wrong length
            "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA" to false, // Bitcoin (version 0x00)
            "0xa614f803B6FD780986A42c78Ec9c7f77e6DeD13C" to false, // EVM form of a Tron account
        )
        cases.forEach { (input, valid) -> assertEquals(valid, TronAddresses.parse(input) != null, input) }
        assertEquals(ours, TronAddresses.fromHex(hex(ours)))
        assertNull(TronAddresses.fromHex("00" + hex(ours).drop(2)))
    }

    private val account = """{"data":[{"address":"${hex(ours)}","balance":12000000,"create_time":1600000000000,
        "frozenV2":[{"amount":5000000},{"type":"ENERGY","amount":3000000},{"type":"TRON_POWER"}],"unfrozenV2":[],
        "trc20":[{"${TronChain.USDT}":"150000000"},{"TSpamToken1111111111111111111111111":"999999"}],
        "assetV2":[]}],"success":true,"meta":{"at":1717000000000,"page_size":1}}"""

    private val trxHistory = """{"data":[
        {"ret":[{"contractRet":"SUCCESS","fee":1100000}],"txID":"t2","net_usage":0,"energy_fee":1100000,"blockNumber":62000000,"block_timestamp":1717000000000,
         "raw_data":{"contract":[{"parameter":{"value":{"data":"a9059cbb","owner_address":"${hex(
        ours,
    )}","contract_address":"${hex(TronChain.USDT)}"},
           "type_url":"type.googleapis.com/protocol.TriggerSmartContract"},"type":"TriggerSmartContract"}],"timestamp":1716999990000}},
        {"ret":[{"contractRet":"SUCCESS","fee":0}],"txID":"t1","blockNumber":61000000,"block_timestamp":1716000000000,
         "raw_data":{"contract":[{"parameter":{"value":{"amount":10000000,"owner_address":"${hex(
        other,
    )}","to_address":"${hex(ours)}"},
           "type_url":"type.googleapis.com/protocol.TransferContract"},"type":"TransferContract"}]}},
        {"internal_tx_id":"x","data":{"note":"63616c6c"},"block_timestamp":1716000000000}],
        "success":true,"meta":{"at":1717000000000,"page_size":3}}"""

    private val trc20History = """{"data":[
        {"transaction_id":"t2","token_info":{"symbol":"USDT","address":"${TronChain.USDT}","decimals":6,"name":"Tether USD"},
         "block_timestamp":1717000000000,"from":"$ours","to":"$other","type":"Transfer","value":"50000000"},
        {"transaction_id":"poison","token_info":{"symbol":"USDT","address":"TSpamToken1111111111111111111111111","decimals":6,"name":"Tether USD"},
         "block_timestamp":1716500000000,"from":"$other","to":"$ours","type":"Transfer","value":"1"},
        {"transaction_id":"t0","token_info":{"symbol":"USDT","address":"${TronChain.USDT}","decimals":6,"name":"Tether USD"},
         "block_timestamp":1715000000000,"from":"$other","to":"$ours","type":"Transfer","value":"200000000"}],
        "success":true,"meta":{"at":1717000000000,"page_size":3}}"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val response = when {
            path.endsWith("/transactions/trc20") -> trc20History
            path.endsWith("/transactions") -> trxHistory
            path == "/v1/accounts/$ours" -> account
            path == "/v1/accounts/$other" -> """{"data":[],"success":true,"meta":{"at":1,"page_size":0}}"""
            else -> error("Unexpected $path")
        }
        respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val adapter = TronAdapter(createHttpClient(engine = engine), "https://tron.test")

    @Test
    fun syncReturnsTrxTokensAndHistory() = runTest {
        withContext(Dispatchers.Default) {
            val result = adapter.sync(assertNotNull(TronAddresses.parse(ours)), emptySet())
            val balances = result.balances.associate { it.asset.symbol to it.amount.toPlainString() }
            assertEquals("20", balances["TRX"], "12 liquid + 8 staked")
            assertEquals("150", balances["USDT"])
            assertEquals("tether", result.balances.single { it.asset.symbol == "USDT" }.asset.priceId)
            assertEquals(2, result.balances.size, "fake USDT from another contract is ignored")

            val byKey = result.transactions.associateBy { it.hash + "/" + it.asset.symbol }
            assertEquals(-1_100_000L, byKey.getValue("t2/TRX").delta.longValue(), "energy fee burned")
            assertEquals(-50_000_000L, byKey.getValue("t2/USDT").delta.longValue())
            assertEquals(10_000_000L, byKey.getValue("t1/TRX").delta.longValue())
            assertEquals(200_000_000L, byKey.getValue("t0/USDT").delta.longValue())
            assertFalse(result.transactions.any { it.hash == "poison" }, "address-poisoning token dropped")
        }
    }

    @Test
    fun unactivatedAccount() = runTest {
        withContext(Dispatchers.Default) {
            val probe = adapter.probe(assertNotNull(TronAddresses.parse(other)))
            assertFalse(probe.hasActivity)
            assertTrue(probe.balances.single().amount.isZero)
        }
    }
}
