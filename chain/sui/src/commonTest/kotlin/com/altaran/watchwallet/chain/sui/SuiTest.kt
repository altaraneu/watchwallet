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

    private val pad2 = "0x0000000000000000000000000000000000000000000000000000000000000002::sui::SUI"

    private fun node(digest: String, changes: String) =
        """{"digest":"$digest","effects":{"timestamp":"2024-05-29T16:26:40Z","balanceChanges":{"nodes":[$changes]}}}"""

    private fun change(owner: String, coin: String, amount: String) =
        """{"owner":{"address":"$owner"},"coinType":{"repr":"$coin"},"amount":"$amount"}"""

    @Test
    fun coinTypesAreNormalized() {
        assertEquals("0x2::sui::SUI", normalizeCoinType(pad2))
        assertEquals(usdc, normalizeCoinType(usdc))
    }

    @Test
    fun syncUsesBalanceChangesFromGraphql() = runTest {
        withContext(Dispatchers.Default) {
            val engine = MockEngine { request ->
                val body = request.body.toByteArray().decodeToString()
                val json = when {
                    "balances(" in body -> {
                        """{"data":{"address":{"balances":{"nodes":[
                        {"coinType":{"repr":"$pad2"},"totalBalance":"1500000000"},
                        {"coinType":{"repr":"$usdc"},"totalBalance":"2500000"}]}}}}"""
                    }

                    "coinMetadata" in body -> {
                        """{"data":{"coinMetadata":{"decimals":6,"symbol":"USDC","name":"USD Coin"}}}"""
                    }

                    "transactions(" in body -> {
                        """{"data":{"address":{"transactions":{"pageInfo":{"hasPreviousPage":false,"startCursor":"c1"},"nodes":[
                        ${node("d1", change(ours, pad2, "1000000000") + "," + change(other, pad2, "-1000000000"))},
                        ${node("d2", change(ours, pad2, "-2000000") + "," + change(ours, usdc, "2500000"))}]}}}}"""
                    }

                    else -> {
                        error("unexpected $body")
                    }
                }
                respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val adapter = SuiAdapter(createHttpClient(engine = engine), "https://sui.test/graphql")
            val target = assertNotNull(SuiAddresses.parse(ours))
            val result = adapter.sync(target, emptySet())
            assertEquals(listOf("SUI", "USDC"), result.balances.map { it.asset.symbol })
            assertEquals("1.5", result.balances.first().amount.toPlainString())
            val byKey = result.transactions.associateBy { it.hash + "/" + it.asset.symbol }
            assertEquals("1000000000", byKey.getValue("d1/SUI").delta.toString())
            assertEquals("-2000000", byKey.getValue("d2/SUI").delta.toString())
            assertEquals("2500000", byKey.getValue("d2/USDC").delta.toString())
        }
    }
}
