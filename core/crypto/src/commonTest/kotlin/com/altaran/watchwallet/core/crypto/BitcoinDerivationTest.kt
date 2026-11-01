package com.altaran.watchwallet.core.crypto

import com.altaran.watchwallet.core.model.ScriptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Official test vectors from BIP44/49/84/86 (mnemonic "abandon … about", account 0).
 * Only the account-level public keys are used here; no private keys are involved.
 */
class BitcoinDerivationTest {
    private fun firstAddresses(key: String, scriptType: ScriptType): Pair<String, String> {
        val parsed = assertNotNull(ExtendedKeys.parse(key), "key must parse: $key")
        return BitcoinAddresses.derive(parsed.key, scriptType, change = false, index = 0) to
            BitcoinAddresses.derive(parsed.key, scriptType, change = true, index = 0)
    }

    @Test
    fun bip84NativeSegwit() {
        val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        val parsed = assertNotNull(ExtendedKeys.parse(zpub))
        assertEquals(ScriptType.P2WPKH, parsed.scriptType)
        val (receive, change) = firstAddresses(zpub, ScriptType.P2WPKH)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", receive)
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", change)
    }

    @Test
    fun bip49NestedSegwit() {
        val ypub = "ypub6Ww3ibxVfGzLrAH1PNcjyAWenMTbbAosGNB6VvmSEgytSER9azLDWCxoJwW7Ke7icmizBMXrzBx9979FfaHxHcrArf3zbeJJJUZPf663zsP"
        assertEquals(ScriptType.P2SH_P2WPKH, ExtendedKeys.parse(ypub)?.scriptType)
        assertEquals("37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf", firstAddresses(ypub, ScriptType.P2SH_P2WPKH).first)
    }

    @Test
    fun bip44LegacyWithAmbiguousXpub() {
        val xpub = "xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj"
        assertNull(ExtendedKeys.parse(xpub)?.scriptType, "xpub prefix does not imply a script type")
        assertEquals("1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA", firstAddresses(xpub, ScriptType.P2PKH).first)
    }

    @Test
    fun bip86Taproot() {
        val xpub = "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"
        assertEquals(
            "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
            firstAddresses(xpub, ScriptType.P2TR).first,
        )
    }

    @Test
    fun slip132KeysNormalizeToXpub() {
        val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        val canonical = assertNotNull(ExtendedKeys.parse(zpub)).canonical
        assertTrue(canonical.startsWith("xpub"), canonical)
        assertEquals(ExtendedKeys.parse(zpub)?.key, ExtendedKeys.parse(canonical)?.key)
    }

    @Test
    fun corruptedKeyIsRejected() {
        val corrupted = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYt"
        assertNull(ExtendedKeys.parse(corrupted))
    }

    @Test
    fun descriptorsCarryTheScriptType() {
        val zpubAsXpub = assertNotNull(
            ExtendedKeys.parse(
                "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
            ),
        ).canonical
        val descriptor = "wpkh([73c5da0a/84'/0'/0']$zpubAsXpub/<0;1>/*)#abcdefgh"
        val parsed = assertNotNull(OutputDescriptors.parse(descriptor))
        assertEquals(ScriptType.P2WPKH, parsed.scriptType)
        assertEquals(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            BitcoinAddresses.derive(parsed.extendedKey.key, parsed.scriptType, change = false, index = 0),
        )
        assertEquals(ScriptType.P2SH_P2WPKH, OutputDescriptors.parse("sh(wpkh($zpubAsXpub/0/*))")?.scriptType)
        assertEquals(ScriptType.P2TR, OutputDescriptors.parse("tr($zpubAsXpub)")?.scriptType)
        assertNull(OutputDescriptors.parse("wsh(multi(2,$zpubAsXpub,$zpubAsXpub))"))
    }

    @Test
    fun addressTypesAreDetectedWithChecksum() {
        assertEquals(ScriptType.P2PKH, BitcoinAddresses.scriptTypeOf("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
        assertEquals(ScriptType.P2SH, BitcoinAddresses.scriptTypeOf("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"))
        assertEquals(ScriptType.P2WPKH, BitcoinAddresses.scriptTypeOf("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        assertEquals(
            ScriptType.P2TR,
            BitcoinAddresses.scriptTypeOf("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"),
        )
        assertNull(BitcoinAddresses.scriptTypeOf("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb"), "wrong checksum")
        assertNull(BitcoinAddresses.scriptTypeOf("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"), "testnet")
    }
}
