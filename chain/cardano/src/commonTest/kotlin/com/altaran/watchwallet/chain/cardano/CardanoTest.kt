// Fixtures contain long real addresses and JSON payloads.
@file:Suppress("ktlint:standard:max-line-length")

package com.altaran.watchwallet.chain.cardano

import com.altaran.watchwallet.chain.common.Bech32Codec
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
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgs68faae" to
                false, // testnet
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

    /** Nothing a server says can become an address offered for receiving money. */
    @Test
    fun aStakeWalletNeverAsksForOrReportsAPaymentAddress() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            val path = request.url.encodedPath.substringAfterLast('/')
            paths += path
            when (path) {
                "account_info" -> respond("""[{"total_balance":"5"}]""", HttpStatusCode.OK, json)
                "account_addresses" -> respond("""[{"stake_address":"$stake","addresses":["$base"]}]""", HttpStatusCode.OK, json)
                else -> respond("[]", HttpStatusCode.OK, json)
            }
        }
        val adapter = CardanoAdapter(createHttpClient(engine = engine), "https://koios.test/api/v1")
        val result = adapter.sync(assertNotNull(CardanoAddresses.parse(stake)), emptySet())
        assertEquals(emptyList(), result.derivedAddresses)
        assertTrue("account_info" in paths, "the sync did run")
        assertTrue("account_addresses" !in paths, "not requested at all")
    }

    private val hunt = "f43a62fdc3965df486de8a0d32fe800963589c41b38946602a0dc535"

    /** A stake key with [total] transactions, numbered newest first; every request is recorded. */
    private class Paged(val stake: String, val total: Int) {
        val infoRequests = mutableListOf<List<String>>()
        var lastHistoryLimit: Int? = null

        val engine = MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.encodedPath.substringAfterLast('/')) {
                "account_info" -> {
                    respond("""[{"total_balance":"5"}]""", HttpStatusCode.OK, json)
                }

                "account_assets" -> {
                    respond("[]", HttpStatusCode.OK, json)
                }

                "account_txs" -> {
                    val limit = request.url.parameters["limit"]!!.toInt()
                    val offset = request.url.parameters["offset"]!!.toInt()
                    lastHistoryLimit = limit
                    val rows = (offset until minOf(offset + limit, total)).joinToString(",") { """{"tx_hash":"t$it"}""" }
                    respond("[$rows]", HttpStatusCode.OK, json)
                }

                "tx_info" -> {
                    val body = request.body.toByteArray().decodeToString()
                    val hashes = Regex("t[0-9]+").findAll(body).map { it.value }.toList()
                    infoRequests += hashes
                    val rows = hashes.joinToString(",") { hash ->
                        """{"tx_hash":"$hash","tx_timestamp":1700000000,"fee":"1","inputs":[],"withdrawals":[],
                            "outputs":[{"payment_addr":{"bech32":"a"},"stake_addr":"$stake","value":"5","asset_list":[]}]}"""
                    }
                    respond("[$rows]", HttpStatusCode.OK, json)
                }

                else -> {
                    error("Unexpected ${request.url}")
                }
            }
        }
    }

    @Test
    fun historyIsContinuedWithoutGaps() = runTest {
        val total = 650
        val chain = Paged(stake, total)
        val adapter = CardanoAdapter(createHttpClient(engine = chain.engine), "https://koios.test/api/v1")
        val target = assertNotNull(CardanoAddresses.parse(stake))
        assertTrue(adapter.supportsHistoryContinuation)

        val first = adapter.sync(target, emptySet())
        assertTrue(first.historyTruncated, "two pages on the first sync")
        val known = first.transactions.map { it.hash }.toMutableSet()
        assertEquals(200, known.size)

        var syncs = 0
        var truncated = true
        while (truncated) {
            val next = adapter.sync(target, known, continueHistory = true)
            assertEquals(1000, chain.lastHistoryLimit, "hashes are walked in the largest pages")
            known += next.transactions.map { it.hash }
            truncated = next.historyTruncated
            assertTrue(++syncs < 10, "must finish")
        }
        assertEquals((0 until total).map { "t$it" }.toSet(), known, "no gap")
        assertEquals(3, syncs, "450 missing at 200 per sync")
    }

    @Test
    fun aGapInTheMiddleIsFoundAndNewTransactionsComeFirst() = runTest {
        val chain = Paged(stake, 300)
        val adapter = CardanoAdapter(createHttpClient(engine = chain.engine), "https://koios.test/api/v1")
        val known = (0 until 300).map { "t$it" }.toSet() - setOf("t0", "t150")
        val next = adapter.sync(assertNotNull(CardanoAddresses.parse(stake)), known, continueHistory = true)
        assertEquals(listOf("t0", "t150"), next.transactions.map { it.hash })
        assertTrue(!next.historyTruncated)
    }

    @Test
    fun aRoutineSyncStopsAtTheKnownTransactions() = runTest {
        val chain = Paged(stake, 300)
        val adapter = CardanoAdapter(createHttpClient(engine = chain.engine), "https://koios.test/api/v1")
        val next = adapter.sync(assertNotNull(CardanoAddresses.parse(stake)), setOf("t2"))
        assertEquals(setOf("t0", "t1"), next.transactions.map { it.hash }.toSet())
        assertTrue(!next.historyTruncated)
    }

    @Test
    fun stakeTargetSync() = runTest {
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            val response = when (request.url.encodedPath.substringAfterLast('/')) {
                "account_info" -> {
                    """[{"stake_address":"$stake","status":"registered","delegated_pool":"pool1xyz","total_balance":"105000000","utxo":"100000000",
                    "rewards":"9000000","withdrawals":"4000000","rewards_available":"5000000","deposit":"2000000","reserves":"0","treasury":"0"}]"""
                }

                "account_assets" -> {
                    """[{"stake_address":"$stake","policy_id":"$hunt","asset_name":"48554e54","fingerprint":"asset1abc","decimals":6,"quantity":"1500000"}]"""
                }

                "account_txs" -> {
                    """[{"tx_hash":"tx2","epoch_no":500,"block_height":11000000,"block_time":1717000000},
                    {"tx_hash":"tx1","epoch_no":499,"block_height":10900000,"block_time":1716000000}]"""
                }

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

                else -> {
                    error("Unexpected ${request.url}")
                }
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
