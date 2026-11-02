package com.altaran.watchwallet.core.data.chain

import com.altaran.watchwallet.core.data.DemoWallets
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.model.WatchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChainParsersValidationTest {
    private val xpub = "xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj"

    /** Everything manual entry produces survives a backup round trip: stored wallets stay restorable. */
    @Test
    fun everyTargetOfManualEntryIsValid() {
        DemoWallets.ENTRIES.forEach { entry ->
            val target = DemoWallets.resolve(entry)
            assertNotNull(entry.name, target)
            assertEquals(entry.name, target, ChainParsers.validated(target!!))
        }
    }

    /** Values that would otherwise end up in request paths (Tezos.kt, Blockbook.kt…). */
    @Test
    fun hostileValuesAreInvalidOnEveryChain() {
        val hostile = listOf(
            "../../../admin",
            "tz1abc/../../account",
            "x?y=1",
            "a#b",
            "a b",
            "a%2F..%2Fb",
            "bitcoincash:../x",
            "",
            "0.0.1/../../x",
            "alice.near/../x",
            "https://evil.example/x",
        )
        val chains = DemoWallets.ENTRIES.mapNotNull { DemoWallets.resolve(it) }.filterIsInstance<WatchTarget.Address>()
            .map { it.family to it.chainId }.distinct()
        check(chains.size > 15)
        chains.forEach { (family, chain) ->
            hostile.forEach { value ->
                assertNull("$family $value", ChainParsers.validated(WatchTarget.Address(family, chain, value)))
            }
        }
    }

    @Test
    fun aValidAddressOfAnotherChainIsInvalid() {
        val btc = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        assertNull(ChainParsers.validated(WatchTarget.Address(ChainFamily.SOLANA, ChainId("solana"), btc)))
        assertNull(ChainParsers.validated(WatchTarget.Address(ChainFamily.UTXO, ChainId("litecoin"), btc)))
        assertNull(ChainParsers.validated(WatchTarget.Address(ChainFamily.EVM, ChainId.evm(1), btc)))
    }

    @Test
    fun extendedKeysAreValidatedForTheirChain() {
        val valid = WatchTarget.ExtendedPublicKey(ChainFamily.UTXO, ChainId.BITCOIN, xpub, ScriptType.P2WPKH)
        assertEquals(valid, ChainParsers.validated(valid))
        assertNull(ChainParsers.validated(valid.copy(key = "xpub123")))
        assertNull(ChainParsers.validated(valid.copy(gapLimit = 0)))
        assertNull("no unbounded requests from one entry", ChainParsers.validated(valid.copy(gapLimit = 1_000_000)))
        assertNull(ChainParsers.validated(valid.copy(chainId = ChainId("solana"))))
    }
}
