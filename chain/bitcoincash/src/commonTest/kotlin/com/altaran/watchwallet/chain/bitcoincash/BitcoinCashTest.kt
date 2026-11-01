package com.altaran.watchwallet.chain.bitcoincash

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitcoinCashTest {
    @Test
    fun addressValidation() {
        // CashAddr specification examples (legacy ↔ CashAddr pairs).
        val cases = listOf(
            "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a" to "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a",
            "qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a" to "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a",
            "BITCOINCASH:QPM2QSZNHKS23Z7629MMS6S4CWEF74VCWVY22GDX6A" to "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a",
            "1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu" to "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a",
            "1KXrWXciRDZUpQwQmuM1DbwsKDLYAYsVLR" to "bitcoincash:qr95sy3j9xwd2ap32xkykttr4cvcu7as4y0qverfuy",
            "16w1D5WRVKJuZUsSRzdLp9w3YGcgoxDXb" to "bitcoincash:qqq3728yw0y47sqn6l2na30mcw6zm78dzqre909m2r",
            "3CWFddi6m4ndiGyKqzYvsFYagqDLPVMTzC" to "bitcoincash:ppm2qsznhks23z7629mms6s4cwef74vcwvn0h829pq",
            "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6b" to null, // wrong checksum
            "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22g" to null, // wrong length
            "bchtest:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a" to null, // wrong network prefix
            "bitcoincash:QPM2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a" to null, // mixed case
            "LUWPbpM43E2p7ZSh8cyTBEkvpHmr3cB8Ez" to null, // Litecoin
        )
        cases.forEach { (input, expected) -> assertEquals(expected, BitcoinCashAddresses.parse(input)?.address, input) }
        assertEquals(ScriptType.P2SH, BitcoinCashAddresses.parse("3CWFddi6m4ndiGyKqzYvsFYagqDLPVMTzC")?.scriptType)
        assertTrue(BitcoinCashAddresses.isCashAddr("qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a"))
        assertFalse(BitcoinCashAddresses.isCashAddr("1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu"))
    }

    @Test
    fun derivationProducesCashAddr() {
        val seed = MnemonicCode.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")
        val account = DeterministicWallet.generate(seed).derivePrivateKey("m/44'/145'/0'")
        val target = assertNotNull(BitcoinCashAddresses.parseExtendedKey(account.extendedPublicKey.encode(DeterministicWallet.xpub)))
        val derived = BitcoinCashAddresses.derive(target.key, false, 0)
        // Cross-check against the legacy P2PKH address of the same key.
        val pub = account.derivePrivateKey(0).derivePrivateKey(0).extendedPublicKey.publicKey
        val legacy = fr.acinq.bitcoin.Bitcoin.computeBIP44Address(pub, fr.acinq.bitcoin.Block.LivenetGenesisBlock.hash)
        assertEquals(BitcoinCashAddresses.parse(legacy)?.address, derived)
        assertTrue(derived.startsWith("bitcoincash:q"))
        assertNull(BitcoinCashAddresses.parseExtendedKey(account.extendedPublicKey.encode(DeterministicWallet.zpub)))
    }

    @Test
    fun adapterNormalizesLegacyInput() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v2/address/bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a", request.url.encodedPath.replace("%3A", ":"))
            respond(
                """{"page":1,"totalPages":1,"itemsOnPage":1000,"address":"bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a",
                   "balance":"12345","totalReceived":"12345","totalSent":"0","unconfirmedBalance":"0","unconfirmedTxs":0,"txs":1}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val adapter = BitcoinCashAdapter(createHttpClient(engine = engine), "https://bch.test")
        val probe = adapter.probe(assertNotNull(BitcoinCashAddresses.parse("1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu")))
        assertTrue(probe.hasActivity)
        assertEquals("0.00012345", probe.balances.single().amount.toPlainString())
    }
}
