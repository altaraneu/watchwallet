package com.altaran.watchwallet.chain.cardano

import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CardanoTest {
    // CIP-19 test vectors (mainnet).
    private val base = "addr1qx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgse35a3x"
    private val enterprise = "addr1vx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzers66hrl8"
    private val stake = "stake1uyehkck0lajq8gr28t9uxnuvgcqrc6070x3k9r8048z8y5gh6ffgw"

    @Test
    fun addressValidation() {
        val cases = listOf(
            base to true,
            enterprise to true,
            stake to true,
            base.uppercase() to true,
            base.dropLast(1) + "y" to false, // wrong checksum
            enterprise.dropLast(8) to false, // truncated
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgs68faae" to false, // testnet
            "stake_test1uqehkck0lajq8gr28t9uxnuvgcqrc6070x3k9r8048z8y5gfjkjnn" to false, // testnet
            "Ae2tdPwUPEZFRbyhz3cpfC2CumGzNkFBN2L42rcUc2yjQpEkxDbkPodpMAi" to false, // Byron (unsupported)
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu" to false, // Bitcoin
        )
        cases.forEach { (input, valid) -> assertEquals(valid, CardanoAddresses.parse(input) != null, input) }
        assertTrue(base.length > 90, "longer than the BIP-173 limit")
    }

    @Test
    fun stakeAddressOfBaseAddress() {
        assertEquals(stake, CardanoAddresses.stakeAddressOf(base))
        assertEquals(null, CardanoAddresses.stakeAddressOf(enterprise))
    }

    private val hunt = "f43a62fdc3965df486de8a0d32fe800963589c41b38946602a0dc535"

    @Test
    fun stakeTargetSync() = runTest {
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            val response = when (request.url.encodedPath.substringAfterLast('/')) {
                "account_info" -> """[{"stake_address":"$stake","status":"registered","delegated_pool":"pool1xyz","total_balance":"105000000","utxo":"100000000",
                    "rewards":"9000000","withdrawals":"4000000","rewards_available":"5000000","deposit":"2000000","reserves":"0","treasury":"0"}]"""
                "account_assets" -> """[{"stake_address":"$stake","policy_id":"$hunt","asset_name":"48554e54","fingerprint":"asset1abc","decimals":6,"quantity":"1500000"}]"""
                "account_txs" -> """[{"tx_hash":"tx2","epoch_no":500,"block_height":11000000,"block_time":1717000000},
                    {"tx_hash":"tx1","epoch_no":499,"block_height":10900000,"block_time":1716000000}]"""
                "tx_info" -> {
                    assertTrue("\"_inputs\":true" in body)
                    """[{"tx_hash":"tx2","block_time":1717000000,"block_height":11000000,"fee":"200000",
                        "inputs":[{"payment_addr":{"bech32":"$base","cred":"x"},"stake_addr":"$stake","value":"50000000","asset_list":[]}],
                        "outputs":[{"payment_addr":{"bech32":"addr1shop","cred":"y"},"stake_addr":null,"value":"10000000","asset_list":[]},
                                   {"payment_addr":{"bech32":"$base","cred":"x"},"stake_addr":"$stake","value":"43800000","asset_list":[]}],
                        "withdrawals":[{"amount":"4000000","stake_addr":"$stake"}]},
                       {"tx_hash":"tx1","block_time":1716000000,"block_height":10900000,"fee":"180000",
                        "inputs":[{"payment_addr":{"bech32":"addr1exchange","cred":"z"},"stake_addr":null,"value":"200000000","asset_list":[]}],
                        "outputs":[{"payment_addr":{"bech32":"$base","cred":"x"},"stake_addr":"$stake","value":"61200000",
                                    "asset_list":[{"policy_id":"$hunt","asset_name":"48554e54","fingerprint":"asset1abc","decimals":6,"quantity":"1500000"}]}],
                        "withdrawals":[]}]"""
                }
                else -> error("Unexpected ${request.url}")
            }
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val adapter = CardanoAdapter(createHttpClient(engine = engine), "https://koios.test/api/v1")
        val result = adapter.sync(assertNotNull(CardanoAddresses.parse(stake)), emptySet())
        assertEquals("105", result.balances[0].amount.toPlainString(), "UTXO + available rewards")
        assertEquals("HUNT", result.balances[1].asset.symbol)
        assertEquals("1.5", result.balances[1].amount.toPlainString())

        val byKey = result.transactions.associateBy { it.hash + "/" + it.asset.symbol }
        // 43.8 back − 50 in − 4 withdrawal (rewards were already counted) = −10.2 = −10 paid − 0.2 fee.
        assertEquals(-10_200_000L, byKey.getValue("tx2/ADA").delta.longValue())
        assertEquals(200_000L, byKey.getValue("tx2/ADA").fee?.longValue())
        assertEquals(61_200_000L, byKey.getValue("tx1/ADA").delta.longValue())
        assertEquals(1_500_000L, byKey.getValue("tx1/HUNT").delta.longValue())
    }
}
