package com.altaran.watchwallet.chain.common

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EncodingsTest {
    @Test
    fun bech32SpecVectors() {
        // BIP-173 / BIP-350 valid checksums.
        listOf(
            "A12UEL5L" to Bech32Codec.Variant.BECH32,
            "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw" to Bech32Codec.Variant.BECH32,
            "a1lqfn3a" to Bech32Codec.Variant.BECH32M,
            "abcdef1l7aum6echk45nj3s0wdvt2fg8x9yrzpqzd3ryx" to Bech32Codec.Variant.BECH32M,
        ).forEach { (text, variant) -> assertEquals(variant, assertNotNull(Bech32Codec.decode(text), text).variant, text) }
        // Mixed case, bad checksum, bad character.
        listOf("A12uEL5L", "a12uel5m", "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxx", "1nwldj5").forEach {
            assertNull(Bech32Codec.decode(it), it)
        }
    }

    @Test
    fun segwitRoundTrip() {
        val (version, program) = assertNotNull(Bech32Codec.decodeSegwit("bc", "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
        assertEquals(0, version)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", Bech32Codec.encodeSegwit("bc", 0, program))
        assertNull(Bech32Codec.decodeSegwit("ltc", "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
    }

    @Test
    fun cashAddrSpecVectors() {
        // Examples from the CashAddr specification (legacy hash → CashAddr).
        val legacyHash = assertNotNull(Base58Checks.decode("1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu")).copyOfRange(1, 21)
        assertEquals(
            "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a",
            CashAddrCodec.encode("bitcoincash", byteArrayOf(0) + legacyHash),
        )
        val (prefix, payload) = assertNotNull(CashAddrCodec.decode("qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a", "bitcoincash"))
        assertEquals("bitcoincash", prefix)
        assertContentEquals(byteArrayOf(0) + legacyHash, payload)
        assertNull(CashAddrCodec.decode("bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6b", "bitcoincash"))
    }

    @Test
    fun crc16Xmodem() {
        assertEquals(0x31C3, Crc16.xmodem("123456789".encodeToByteArray()))
    }

    @Test
    fun base32RoundTrip() {
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".encodeToByteArray()))
        assertContentEquals("foobar".encodeToByteArray(), Base32.decode("MZXW6YTBOI"))
        assertNull(Base32.decode("mzxw"))
    }

    @Test
    fun base58CheckAlphabets() {
        val payload = byteArrayOf(0) + ByteArray(20) { it.toByte() }
        val xrp = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz"
        val encoded = Base58Checks.encode(payload, xrp)
        assertEquals('r', encoded.first())
        assertContentEquals(payload, Base58Checks.decode(encoded, xrp))
        assertNull(Base58Checks.decode(encoded.dropLast(1) + if (encoded.last() == 'r') 'p' else 'r', xrp))
    }
}
