// Fixtures contain long real addresses and JSON payloads.
@file:Suppress("ktlint:standard:max-line-length")

package com.altaran.watchwallet.chain.sui

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
import kotlin.test.assertNotNull

class SuiTest {
    private val ours = "0x02a212de6a9dfa3a69e22387acfbafbb1a9e591bd9d636e7895dcfc8de05f331"
    private val other = "0xac5bceec1b789ff840d7d4e6ce4ce61c90d190a7f8c4f4ddf0bff6ee2413c33c"
    private val usdc = "0xdba34672e30cb065b1f93e3ab55318768fd6fef66c15942c9f7cb846e2f900e7::usdc::USDC"

    @Test
    fun addressValidation() {
        val cases = listOf(
            ours to ours,
            ours.uppercase().replace("0X", "0x") to ours,
            "0x02a212de6a9dfa3a69e22387acfbafbb1a9e591bd9d636e7895dcfc8de05f33" to null, // 63 hex
            "0x02a212de6a9dfa3a69e22387acfbafbb1a9e591bd9d636e7895dcfc8de05f3311" to null, // 65 hex
            "02a212de6a9dfa3a69e22387acfbafbb1a9e591bd9d636e7895dcfc8de05f331" to null, // no 0x (private-key shape)
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed" to null, // EVM
            "0xg2a212de6a9dfa3a69e22387acfbafbb1a9e591bd9d636e7895dcfc8de05f331" to null, // not hex
        )
        cases.forEach { (input, expected) -> assertEquals(expected, SuiAddresses.parse(input)?.address, input) }
    }

    private fun block(digest: String, sender: String, changes: String) = """{"digest":"$digest","timestampMs":"1717000000000","checkpoint":"1",
        "effects":{"status":{"status":"success"},"gasUsed":{"computationCost":"750000","storageCost":"1976000","storageRebate":"978120","nonRefundableStorageFee":"9880"},
          "gasObject":{"owner":{"AddressOwner":"$sender"},"reference":{"objectId":"0x1","version":1,"digest":"x"}}},
        "balanceChanges":[$changes]}"""

    private val sendBlock = block(
        "D2",
        ours,
        """{"owner":{"AddressOwner":"$ours"},"coinType":"0x2::sui::SUI","amount":"-1001747880"},
           {"owner":{"AddressOwner":"$other"},"coinType":"0x2::sui::SUI","amount":"1000000000"}""",
    )
    private val receiveBlock = block(
        "D1",
        other,
        """{"owner":{"AddressOwner":"$other"},"coinType":"$usdc","amount":"-5000000"},
           {"owner":{"AddressOwner":"$ours"},"coinType":"$usdc","amount":"5000000"}""",
    )

    private val engine = MockEngine { request ->
        val body = request.body.toByteArray().decodeToString()
        val result = when {
            "suix_getAllBalances" in body -> {
                """[{"coinType":"0x2::sui::SUI","coinObjectCount":2,"totalBalance":"3500000000","lockedBalance":{}},
                {"coinType":"$usdc","coinObjectCount":1,"totalBalance":"5000000","lockedBalance":{}},
                {"coinType":"0xbad::spam::SPAM","coinObjectCount":1,"totalBalance":"100","lockedBalance":{}}]"""
            }

            "suix_getCoinMetadata" in body && "usdc" in body -> {
                """{"decimals":6,"name":"USDC","symbol":"USDC","description":"USDC","iconUrl":null,"id":"0x1"}"""
            }

            "suix_getCoinMetadata" in body -> {
                "null"
            }

            "FromAddress" in body -> {
                """{"data":[$sendBlock],"nextCursor":"D2","hasNextPage":false}"""
            }

            "ToAddress" in body -> {
                """{"data":[$sendBlock,$receiveBlock],"nextCursor":"D1","hasNextPage":false}"""
            }

            else -> {
                error("Unexpected $body")
            }
        }
        respond(
            """{"jsonrpc":"2.0","id":1,"result":$result}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    @Test
    fun syncUsesBalanceChanges() = runTest {
        withContext(Dispatchers.Default) {
            val adapter = SuiAdapter(createHttpClient(engine = engine), "https://sui.test")
            val result = adapter.sync(assertNotNull(SuiAddresses.parse(ours)), emptySet())
            assertEquals(
                listOf("SUI", "USDC"),
                result.balances.map {
                    it.asset.symbol
                },
                "coin without metadata skipped",
            )
            assertEquals("3.5", result.balances[0].amount.toPlainString())

            val byKey = result.transactions.associateBy { it.hash + "/" + it.asset.symbol }
            assertEquals(2, byKey.size, "send block merged from both queries")
            assertEquals(-1_001_747_880L, byKey.getValue("D2/SUI").delta.longValue())
            assertEquals(1_747_880L, byKey.getValue("D2/SUI").fee?.longValue())
            assertEquals(5_000_000L, byKey.getValue("D1/USDC").delta.longValue())
        }
    }
}
