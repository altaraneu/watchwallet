package com.altaran.watchwallet.chain.ton

import com.altaran.watchwallet.chain.common.Base64Codec
import com.altaran.watchwallet.chain.common.Crc16
import com.altaran.watchwallet.chain.common.Hex
import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class TonTest {
    private val usdtMaster = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs"
    private val ours = TonAddresses.toFriendly("0:" + "6F".repeat(32), bounceable = false)!!

    private fun testnet(friendly: String): String {
        val bytes = assertNotNull(Base64Codec.decode(friendly)).copyOfRange(0, 34)
        bytes[0] = (bytes[0].toInt() or 0x80).toByte()
        val crc = Crc16.xmodem(bytes)
        return Base64Codec.encode(bytes + byteArrayOf((crc shr 8).toByte(), crc.toByte()), urlSafe = true)
    }

    @Test
    fun addressValidation() {
        val raw = assertNotNull(TonAddresses.toRaw(usdtMaster))
        assertEquals("0:B113A994B5024A16719F69139328EB759596C38A25F59028B146FECDC3621DFE", raw)
        val nonBounceable = assertNotNull(TonAddresses.toFriendly(usdtMaster, bounceable = false))
        assertTrue(nonBounceable.startsWith("UQ"))
        val cases = listOf(
            usdtMaster to true,
            nonBounceable to true,
            raw to true,
            raw.lowercase() to true,
            "-1:" + "3".repeat(64) to true, // masterchain
            usdtMaster.dropLast(1) + "t" to false, // wrong checksum
            usdtMaster.dropLast(2) to false, // wrong length
            testnet(usdtMaster) to false, // testnet flag
            "1:" + "3".repeat(64) to false, // unknown workchain
            "0:" + "3".repeat(63) to false,
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed" to false, // EVM
        )
        cases.forEach { (input, valid) -> assertEquals(valid, TonAddresses.parse(input) != null, input) }
        assertEquals(raw.lowercase(), TonAddresses.parse(raw)?.address)
        assertEquals(usdtMaster, TonAddresses.toFriendly(raw, bounceable = true))
    }

    @Test
    fun hashesAreHex() {
        val bytes = ByteArray(32) { it.toByte() }
        assertEquals(Hex.encode(bytes), hashHex(Base64Codec.encode(bytes)))
    }

    private fun json(body: String) = body

    @Test
    fun syncParsesBalancesTransactionsAndJettons() = runTest {
        val oursRaw = assertNotNull(TonAddresses.toRaw(ours))
        val usdtRaw = assertNotNull(TonAddresses.toRaw(usdtMaster))
        val other = "0:" + "AB".repeat(32)
        val hashOut = Base64Codec.encode(ByteArray(32) { 1 })
        val hashIn = Base64Codec.encode(ByteArray(32) { 2 })
        val hashJetton = Base64Codec.encode(ByteArray(32) { 3 })
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/v3/account" -> json(
                    """{"balance":"4500000000","code":null,"data":null,"last_transaction_lt":"1","status":"active"}""",
                )

                "/api/v3/jetton/wallets" -> json(
                    """{"jetton_wallets":[{"address":"0:${"CD".repeat(
                        32,
                    )}","balance":"25000000","owner":"$oursRaw","jetton":"$usdtRaw","last_transaction_lt":"1"},
                       {"address":"0:${"EF".repeat(
                        32,
                    )}","balance":"7","owner":"$oursRaw","jetton":"0:${"99".repeat(32)}"}],
                       "address_book":{},"metadata":{"0:${"99".repeat(
                        32,
                    )}":{"is_indexed":true,"token_info":[{"name":"Some Jetton","symbol":"SJ","type":"jetton_masters","extra":{"decimals":"2"}}]}}}""",
                )

                "/api/v3/transactions" -> json(
                    """{"transactions":[
                       {"account":"$oursRaw","hash":"$hashOut","lt":"3","now":1717000000,"total_fees":"5000000",
                        "account_state_before":{"balance":"5505000000"},"account_state_after":{"balance":"4500000000"},
                        "in_msg":{"source":null,"destination":"$oursRaw","value":null},
                        "out_msgs":[{"source":"$oursRaw","destination":"$other","value":"1000000000"}],"description":{"aborted":false}},
                       {"account":"$oursRaw","hash":"$hashIn","lt":"2","now":1716000000,"total_fees":"100000",
                        "in_msg":{"source":"$other","destination":"$oursRaw","value":"2000000000"},"out_msgs":[]}],"address_book":{}}""",
                )

                "/api/v3/jetton/transfers" -> json(
                    """{"jetton_transfers":[{"query_id":"0","source":"$other","destination":"$oursRaw","amount":"25000000","source_wallet":"0:${"11".repeat(
                        32,
                    )}",
                       "jetton_master":"$usdtRaw","transaction_hash":"$hashJetton","transaction_lt":"1","transaction_now":1715000000,"transaction_aborted":false}]}""",
                )

                else -> error("Unexpected ${request.url}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val adapter =
            TonAdapter(createHttpClient(engine = engine), "https://ton.test/api/v3", minInterval = 1.milliseconds)
        val result = adapter.sync(assertNotNull(TonAddresses.parse(ours)), emptySet())
        assertEquals(listOf("TON", "USDT", "SJ"), result.balances.map { it.asset.symbol })
        assertEquals("4.5", result.balances[0].amount.toPlainString())
        assertEquals("25", result.balances[1].amount.toPlainString())
        assertEquals("0.07", result.balances[2].amount.toPlainString(), "decimals from metadata")

        val byHash = result.transactions.associateBy { it.hash }
        assertEquals(-1_005_000_000L, byHash.getValue(Hex.encode(ByteArray(32) { 1 })).delta.longValue())
        assertEquals(5_000_000L, byHash.getValue(Hex.encode(ByteArray(32) { 1 })).fee?.longValue())
        assertEquals(
            1_999_900_000L,
            byHash.getValue(
                Hex.encode(
                    ByteArray(32) {
                        2
                    },
                ),
            ).delta.longValue(),
            "fallback: value − fees",
        )
        val jetton = byHash.getValue(Hex.encode(ByteArray(32) { 3 }))
        assertEquals("USDT", jetton.asset.symbol)
        assertEquals(25_000_000L, jetton.delta.longValue())
    }

    @Test
    fun unknownAccountIsInactive() = runTest {
        val engine =
            MockEngine {
                respond(
                    """{"error":"not found"}""",
                    HttpStatusCode.NotFound,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val probe = TonAdapter(
            createHttpClient(engine = engine),
            "https://ton.test/api/v3",
            minInterval = 1.milliseconds,
        )
            .probe(assertNotNull(TonAddresses.parse(ours)))
        assertFalse(probe.hasActivity)
        assertNull(probe.transactionCount)
    }
}
