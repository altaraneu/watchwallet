package com.altaran.watchwallet.core.classifier

import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.security.SecretKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AddressClassifierTest {
    private fun single(input: String): WatchTarget {
        val result = assertIs<Classification.Candidates>(AddressClassifier.classify(input), input)
        assertEquals(1, result.candidates.size, input)
        return result.candidates.single().target
    }

    @Test
    fun bitcoinAddressesOfEveryType() {
        val cases = mapOf(
            "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa" to ScriptType.P2PKH,
            "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy" to ScriptType.P2SH,
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq" to ScriptType.P2WPKH,
            "BC1QAR0SRRR7XFKVY5L643LYDNW9RE59GTZZWF5MDQ" to ScriptType.P2WPKH,
            "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3" to ScriptType.P2WSH,
            "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr" to ScriptType.P2TR,
        )
        cases.forEach { (address, type) ->
            val target = assertIs<WatchTarget.Address>(single(address))
            assertEquals(type, target.scriptType, address)
            assertEquals(ChainFamily.UTXO, target.family)
        }
        // bech32 addresses are normalized to lowercase
        assertEquals("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", (single("BC1QAR0SRRR7XFKVY5L643LYDNW9RE59GTZZWF5MDQ") as WatchTarget.Address).address)
    }

    @Test
    fun paymentUrisAreStripped() {
        val target = assertIs<WatchTarget.Address>(single("bitcoin:bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq?amount=0.01&label=Test"))
        assertEquals("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", target.address)
        val evm = assertIs<WatchTarget.Address>(single("ethereum:0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed@137/transfer?value=1"))
        assertEquals("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", evm.address)
    }

    @Test
    fun extendedKeysWithAndWithoutScriptType() {
        val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        val target = assertIs<WatchTarget.ExtendedPublicKey>(single(zpub))
        assertEquals(ScriptType.P2WPKH, target.scriptType)

        val xpub = "xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj"
        val ambiguous = assertIs<Classification.Candidates>(AddressClassifier.classify(xpub))
        assertEquals(4, ambiguous.candidates.size, "bare xpub → all script types")
        assertEquals(Confidence.POSSIBLE, ambiguous.candidates.first().confidence)
    }

    @Test
    fun evmAddressesAndChecksum() {
        val target = assertIs<WatchTarget.Address>(single("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"))
        assertEquals(ChainFamily.EVM, target.family)
        assertEquals("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", target.address, "stored checksummed")
        assertEquals(Classification.WrongChecksum, AddressClassifier.classify("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"))
    }

    @Test
    fun prefixedHexOf64CharactersIsRejectedLikeAnyKey() {
        // Identical to a Sui or Aptos address, which has its own deliberately chosen route.
        assertEquals(Classification.Secret(SecretKind.HEX_PRIVATE_KEY), AddressClassifier.classify("0x" + "A1".repeat(32)))
        assertEquals(Classification.Secret(SecretKind.HEX_PRIVATE_KEY), AddressClassifier.classify("ethereum:0x" + "a1".repeat(32)))
    }

    @Test
    fun secretsAreReportedBeforeAnythingElse() {
        assertEquals(
            Classification.Secret(SecretKind.HEX_PRIVATE_KEY),
            AddressClassifier.classify("0c28fca386c7a227600b2fe50b7cae11ec86d3bf1fbe471be89827e19d72aa1d"),
        )
        assertIs<Classification.Secret>(
            AddressClassifier.classify("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"),
        )
    }

    @Test
    fun otherChainsAreRecognizedAsUnsupportedForNow() {
        mapOf(
            "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU" to ChainFamily.SOLANA,
            "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh" to ChainFamily.XRP,
            "TLyqzVGLV1srkB7dToTAEqgDSfPtXRJZYH" to ChainFamily.TRON,
            "GA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJVSGZ" to ChainFamily.STELLAR,
            "cosmos1vqpjljwsynsn58dugz0w8ut7kun7t8ls2qkmsq" to ChainFamily.COSMOS,
        ).forEach { (input, family) ->
            assertEquals(Classification.Unsupported(family), AddressClassifier.classify(input), input)
        }
    }

    @Test
    fun nearMissesAreRejected() {
        listOf(
            "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb", // wrong checksum
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdx", // wrong bech32 checksum
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeA", // too short
            "hello",
        ).forEach { input ->
            val result = AddressClassifier.classify(input)
            assertIs<Classification.Unrecognized>(result, "$input → $result")
        }
    }

    @Test
    fun multipleEntriesSwitchToBatch() {
        val result = AddressClassifier.classify("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa\n0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed")
        assertEquals(2, assertIs<Classification.Multiple>(result).entries.size)
    }
}
