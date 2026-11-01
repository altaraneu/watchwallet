package com.altaran.watchwallet.chain.litecoin

import com.altaran.watchwallet.chain.common.Base58Checks
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.network.createHttpClient
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Account keys of the public BIP39 test mnemonic ("abandon … about"), never a real wallet. */
internal fun testAccountKey(path: String, version: Int): String {
    val seed = MnemonicCode.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")
    return DeterministicWallet.generate(seed).derivePrivateKey(path).extendedPublicKey.encode(version)
}

class LitecoinTest {
    @Test
    fun addressValidation() {
        val cases = listOf(
            "LUWPbpM43E2p7ZSh8cyTBEkvpHmr3cB8Ez" to ScriptType.P2PKH,
            "M7wtsL7wSHDBJVMWWhtQfTMSYYkyooAAXM" to ScriptType.P2SH,
            "ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh" to ScriptType.P2WPKH,
            "LTC1QJMXNZ78NMC8NQ77WUXH25N2ES7RZM5C2RKK4WH" to ScriptType.P2WPKH,
            "LUWPbpM43E2p7ZSh8cyTBEkvpHmr3cB8Eq" to null, // wrong checksum
            "LUWPbpM43E2p7ZSh8cyTBEkvpHmr3cB8" to null, // wrong length
            "ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wx" to null, // wrong bech32 checksum
            "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA" to null, // Bitcoin
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu" to null, // Bitcoin
            "DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC" to null, // Dogecoin
        )
        cases.forEach { (input, type) -> assertEquals(type, LitecoinAddresses.parse(input)?.scriptType, input) }
        assertEquals("ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh", LitecoinAddresses.parse("LTC1QJMXNZ78NMC8NQ77WUXH25N2ES7RZM5C2RKK4WH")?.address)
    }

    @Test
    fun legacyP2shIsConvertedToM() {
        val hash = assertNotNull(Base58Checks.decode("M7wtsL7wSHDBJVMWWhtQfTMSYYkyooAAXM")).copyOfRange(1, 21)
        val legacy = Base58Checks.encode(byteArrayOf(0x05) + hash)
        assertTrue(legacy.startsWith("3"))
        assertEquals("M7wtsL7wSHDBJVMWWhtQfTMSYYkyooAAXM", LitecoinAddresses.parse(legacy)?.address)
    }

    @Test
    fun derivationMatchesKnownAddresses() {
        val ltub = testAccountKey("m/44'/2'/0'", 0x019DA462)
        assertTrue(ltub.startsWith("Ltub"))
        val legacy = assertNotNull(LitecoinAddresses.parseExtendedKey(ltub))
        assertEquals(ScriptType.P2PKH, legacy.scriptType)
        assertEquals("LUWPbpM43E2p7ZSh8cyTBEkvpHmr3cB8Ez", LitecoinAddresses.derive(legacy.key, legacy.scriptType, false, 0))

        val mtub = testAccountKey("m/49'/2'/0'", 0x01B26EF6)
        val nested = assertNotNull(LitecoinAddresses.parseExtendedKey(mtub))
        assertEquals(ScriptType.P2SH_P2WPKH, nested.scriptType)
        val nestedAddress = LitecoinAddresses.derive(nested.key, nested.scriptType, false, 0)
        assertEquals("M7wtsL7wSHDBJVMWWhtQfTMSYYkyooAAXM", nestedAddress)
        // Cross-check: same script hash as bitcoin-kmp's BIP49 address of the same public key.
        val pub = fr.acinq.bitcoin.DeterministicWallet.ExtendedPublicKey.decode(nested.key).second.derivePublicKey(0).derivePublicKey(0).publicKey
        val btc = fr.acinq.bitcoin.Bitcoin.computeBIP49Address(pub, fr.acinq.bitcoin.Block.LivenetGenesisBlock.hash)
        assertTrue(Base58Checks.decode(btc)!!.copyOfRange(1, 21).contentEquals(Base58Checks.decode(nestedAddress)!!.copyOfRange(1, 21)))

        val zpub = testAccountKey("m/84'/2'/0'", 0x04B24746)
        val segwit = assertNotNull(LitecoinAddresses.parseExtendedKey(zpub))
        assertEquals(ScriptType.P2WPKH, segwit.scriptType)
        assertEquals("ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh", LitecoinAddresses.derive(segwit.key, segwit.scriptType, false, 0))
        assertTrue(segwit.key.startsWith("xpub"), "stored in canonical xpub form")
    }

    @Test
    fun extendedKeyValidation() {
        val ltub = testAccountKey("m/44'/2'/0'", 0x019DA462)
        assertNull(LitecoinAddresses.parseExtendedKey(ltub.dropLast(1) + if (ltub.last() == 'a') 'b' else 'a'), "checksum")
        assertNull(LitecoinAddresses.parseExtendedKey(testAccountKey("m/44'/3'/0'", 0x02FACAFD)), "dgub is not Litecoin")
        assertNull(LitecoinAddresses.parseExtendedKey(ltub, ScriptType.P2TR), "taproot not derivable")
        assertTrue(LitecoinAddresses.isLitecoinSpecificKey(ltub))
    }

    @Test
    fun adapterScansDerivedAddressesOneByOne() = runTest {
        val zpub = testAccountKey("m/84'/2'/0'", 0x04B24746)
        val target = assertNotNull(LitecoinAddresses.parseExtendedKey(zpub))
        val first = "ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh"
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path == "/api/address/$first" ->
                    """{"address":"$first","chain_stats":{"funded_txo_count":1,"funded_txo_sum":500000,"spent_txo_count":0,"spent_txo_sum":0,"tx_count":1},
                       "mempool_stats":{"funded_txo_count":0,"funded_txo_sum":0,"spent_txo_count":0,"spent_txo_sum":0,"tx_count":0}}"""
                path == "/api/address/$first/txs" ->
                    """[{"txid":"aa","version":2,"locktime":0,"size":222,"weight":561,"fee":300,
                        "vin":[{"txid":"bb","vout":0,"prevout":{"scriptpubkey_address":"ltc1qsender","value":900000},"is_coinbase":false}],
                        "vout":[{"scriptpubkey_address":"$first","value":500000},{"scriptpubkey_address":"ltc1qsender","value":399700}],
                        "status":{"confirmed":true,"block_height":2700000,"block_time":1710000000}}]"""
                path.endsWith("/txs") -> "[]"
                else -> """{"chain_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val adapter = LitecoinAdapter(createHttpClient(engine = engine), "https://ltc.test/api")
        val result = adapter.sync(target, emptySet())
        assertEquals("0.005", result.balances.single().amount.toPlainString())
        assertEquals(500_000, result.transactions.single().delta.longValue())
        assertEquals(60, result.derivedAddresses.size, "40 receive (used index 0 extends the window) + 20 change")
        assertEquals(1, result.derivedAddresses.count { it.used })
        assertTrue(engine.requestHistory.none { "pub" in it.url.toString() }, "extended key never sent")
    }
}
