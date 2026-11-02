package com.altaran.watchwallet.core.importexport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExportedCostTest {
    private val hash = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val seed = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun assetIdsOfEveryChainFormatRoundTrip() {
        listOf(
            "bitcoin:native",
            "evm:1:native",
            "evm:1:0xdac17f958d2ee523a2206206994597c13d831ec7",
            "solana:epjfwdd5aufqssqem2qn1xzybapc8g4weggkzwytdt1v",
            "cardano:f43a62fdc3965df486de8a0d32fe800963589c41b38946602a0dc53548554e54",
            "cosmos:ibc/27394fb092d2eccd56123c74f36e4c1f926001ceada9ca97ea622b25f41e5eb2",
            "osmosis:factory/osmo1abc/token",
            "sui:0x2.sui.sui",
            "sui:0xabc.pool.lp<0x2.sui.sui,0xdef.coin.coin>",
            "aptos:0x1.aptos_coin.aptoscoin",
            "stellar:usdc-gbbd47if6lwk7p7mdevscwr7dpuwv3nu8cvftc2",
            "hedera:0.0.456858",
            "algorand:31566704",
            "tron:1002000",
        ).forEach { id ->
            assertEquals(hash to id, ExportedCost.of(hash, id, 1.0).decoded(), id)
        }
    }

    @Test
    fun freeTextInTheAssetIdIsRejected() {
        listOf(
            "free text",
            "bitcoin:free text here",
            seed,
            "bitcoin:$seed",
            "bitcoin:" + seed.replace(' ', '-'),
            "bitcoin:" + seed.replace(' ', '.'),
            "bitcoin:native\nsecond line",
            "no-chain-separator",
            "bitcoin:",
            ":native",
            "BITCOIN:native",
            "bitcoin:" + "a".repeat(300),
        ).forEach { id ->
            assertNull(ExportedCost.of(hash, id, 1.0).decoded(), id)
        }
    }

    @Test
    fun aSeedHiddenAsTheHashIsRejected() {
        assertNull(ExportedCost.of(seed.replace(' ', '-').take(120), "bitcoin:native", 1.0).decoded())
    }

    @Test
    fun malformedRefsAreRejected() {
        assertNull(ExportedCost("cost-zz", 1.0).decoded())
        assertNull(ExportedCost("nope", 1.0).decoded())
        assertNull(ExportedCost.of(hash, "bitcoin:native", -1.0).decoded())
        assertNull(ExportedCost.of(hash, "bitcoin:native", Double.NaN).decoded())
    }
}
