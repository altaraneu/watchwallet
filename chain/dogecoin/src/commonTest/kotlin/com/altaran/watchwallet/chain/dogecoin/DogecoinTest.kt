package com.altaran.watchwallet.chain.dogecoin

import com.altaran.watchwallet.chain.common.Base58Checks
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.model.WatchTarget
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DogecoinTest {
    /** Account key of the public BIP39 test mnemonic ("abandon … about"), never a real wallet. */
    private fun testAccountKey(version: Int): String {
        val seed = MnemonicCode.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")
        return DeterministicWallet.generate(seed).derivePrivateKey("m/44'/3'/0'").extendedPublicKey.encode(version)
    }

    @Test
    fun addressValidation() {
        val cases = listOf(
            "DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC" to ScriptType.P2PKH,
            "DH5yaieqoZN36fDVciNyRueRGvGLR3mr7L" to ScriptType.P2PKH,
            Base58Checks.encode(byteArrayOf(0x16) + ByteArray(20) { 7 }) to ScriptType.P2SH,
            "DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgD" to null, // wrong checksum
            "DBus3bamQjgJULBJtYXpEzDWQRwF5iwx" to null, // wrong length
            "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA" to null, // Bitcoin
            "LUWPbpM43E2p7ZSh8cyTBEkvpHmr3cB8Ez" to null, // Litecoin
        )
        cases.forEach { (input, type) -> assertEquals(type, DogecoinAddresses.parse(input)?.scriptType, input) }
    }

    @Test
    fun derivationMatchesKnownAddress() {
        val dgub = testAccountKey(0x02FACAFD)
        assertTrue(dgub.startsWith("dgub"))
        assertTrue(DogecoinAddresses.isDogecoinSpecificKey(dgub))
        val target = assertNotNull(DogecoinAddresses.parseExtendedKey(dgub))
        assertEquals("DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC", DogecoinAddresses.derive(target.key, false, 0))
        // The same account exported as xpub gives the same addresses.
        val fromXpub = assertNotNull(DogecoinAddresses.parseExtendedKey(testAccountKey(DeterministicWallet.xpub)))
        assertEquals(target.key, fromXpub.key)
        assertNull(DogecoinAddresses.parseExtendedKey(testAccountKey(DeterministicWallet.zpub)), "no SegWit on Dogecoin")
    }

    @Test
    fun probeOfUnusedXpubAsksOnlyThreeAddresses() = runTest {
        val engine = MockEngine { request ->
            assertFalse("pub" in request.url.toString())
            respond(
                """{"page":1,"totalPages":1,"itemsOnPage":1000,"address":"x","balance":"0","totalReceived":"0","totalSent":"0","unconfirmedBalance":"0","unconfirmedTxs":0,"txs":0}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val adapter = DogecoinAdapter(createHttpClient(engine = engine), "https://doge.test")
        val target = assertNotNull(DogecoinAddresses.parseExtendedKey(testAccountKey(0x02FACAFD)))
        val probe = adapter.probe(target)
        assertFalse(probe.hasActivity)
        assertEquals(3, engine.requestHistory.size)
        assertTrue(engine.requestHistory.first().url.encodedPath.endsWith("/api/v2/address/DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC"))
    }

    @Test
    fun singleAddressTarget() {
        val target = assertNotNull(DogecoinAddresses.parse("DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC"))
        assertEquals(WatchTarget.Address(target.family, DogecoinChain.ID, "DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC", ScriptType.P2PKH), target)
    }
}
