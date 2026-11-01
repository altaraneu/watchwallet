package com.altaran.watchwallet.chain.common

import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.Crypto

// Checksummed text encodings shared by several chain modules. These are encodings and
// checksums only: no elliptic-curve math and no key material is ever handled here.

// ---------------------------------------------------------------- Hex

object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach {
            append(DIGITS[(it.toInt() shr 4) and 0x0F])
            append(DIGITS[it.toInt() and 0x0F])
        }
    }

    /** Decodes an even-length hex string (optional `0x` prefix); `null` if malformed. */
    fun decode(text: String): ByteArray? {
        val body = text.removePrefix("0x").removePrefix("0X")
        if (body.length % 2 != 0) return null
        val out = ByteArray(body.length / 2)
        for (i in out.indices) {
            val hi = body[2 * i].digitToIntOrNull(16) ?: return null
            val lo = body[2 * i + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

// ---------------------------------------------------------------- Bech32 / Bech32m

/**
 * BIP-173 / BIP-350 Bech32 without the 90-character limit (Cardano addresses are longer).
 * Mixed case is rejected, as required by the specification.
 */
object Bech32Codec {
    const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    enum class Variant(internal val constant: Int) { BECH32(1), BECH32M(0x2bc830a3) }

    class Decoded(val hrp: String, val data: ByteArray, val variant: Variant)

    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GENERATOR[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        hrp.forEachIndexed { i, c ->
            out[i] = c.code ushr 5
            out[i + hrp.length + 1] = c.code and 31
        }
        return out
    }

    /** Encodes 5-bit groups. */
    fun encode(hrp: String, data: ByteArray, variant: Variant = Variant.BECH32): String {
        val values = hrpExpand(hrp) + data.map { it.toInt() }.toIntArray() + IntArray(6)
        val mod = polymod(values) xor variant.constant
        return buildString {
            append(hrp)
            append('1')
            data.forEach { append(CHARSET[it.toInt()]) }
            for (i in 0 until 6) append(CHARSET[(mod ushr (5 * (5 - i))) and 31])
        }
    }

    /** Decodes and verifies the checksum; returns 5-bit groups without the checksum. */
    fun decode(input: String): Decoded? {
        if (input.any { it.code < 33 || it.code > 126 }) return null
        if (input.lowercase() != input && input.uppercase() != input) return null
        val text = input.lowercase()
        val separator = text.lastIndexOf('1')
        if (separator < 1 || separator + 7 > text.length) return null
        val hrp = text.substring(0, separator)
        val data = ByteArray(text.length - separator - 1)
        for (i in data.indices) {
            val value = CHARSET.indexOf(text[separator + 1 + i])
            if (value < 0) return null
            data[i] = value.toByte()
        }
        val check = polymod(hrpExpand(hrp) + data.map { it.toInt() }.toIntArray())
        val variant = Variant.entries.firstOrNull { it.constant == check } ?: return null
        return Decoded(hrp, data.copyOfRange(0, data.size - 6), variant)
    }

    /** Maps Bech32 characters to 5-bit groups; `null` on an invalid character. */
    fun toGroups(text: String): ByteArray? {
        val out = ByteArray(text.length)
        for (i in text.indices) {
            val value = CHARSET.indexOf(text[i])
            if (value < 0) return null
            out[i] = value.toByte()
        }
        return out
    }

    /** Regroups bits (8→5 with padding when encoding, 5→8 strict when decoding). */
    fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): ByteArray? {
        var acc = 0
        var bits = 0
        val maxValue = (1 shl to) - 1
        val out = ArrayList<Byte>(data.size * from / to + 1)
        for (b in data) {
            val value = b.toInt() and 0xFF
            if (value ushr from != 0) return null
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out += ((acc ushr bits) and maxValue).toByte()
            }
        }
        if (pad) {
            if (bits > 0) out += ((acc shl (to - bits)) and maxValue).toByte()
        } else if (bits >= from || ((acc shl (to - bits)) and maxValue) != 0) {
            return null
        }
        return out.toByteArray()
    }

    fun encodeBytes(hrp: String, bytes: ByteArray, variant: Variant = Variant.BECH32): String =
        encode(hrp, convertBits(bytes, 8, 5, pad = true)!!, variant)

    /** Decodes a Bech32 string whose payload is plain bytes (Cosmos, Cardano). */
    fun decodeBytes(input: String): Pair<String, ByteArray>? {
        val decoded = decode(input) ?: return null
        if (decoded.variant != Variant.BECH32) return null
        val bytes = convertBits(decoded.data, 5, 8, pad = false) ?: return null
        return decoded.hrp to bytes
    }

    /** Encodes a SegWit address (witness version + program). */
    fun encodeSegwit(hrp: String, version: Int, program: ByteArray): String {
        val variant = if (version == 0) Variant.BECH32 else Variant.BECH32M
        return encode(hrp, byteArrayOf(version.toByte()) + convertBits(program, 8, 5, pad = true)!!, variant)
    }

    /** Decodes a SegWit address for the given hrp: (version, program) or `null`. */
    fun decodeSegwit(hrp: String, input: String): Pair<Int, ByteArray>? {
        if (input.length > MAX_SEGWIT_LENGTH) return null
        val decoded = decode(input) ?: return null
        if (decoded.hrp != hrp || decoded.data.isEmpty()) return null
        val version = decoded.data[0].toInt()
        if (version > 16) return null
        val program = convertBits(decoded.data.copyOfRange(1, decoded.data.size), 5, 8, pad = false) ?: return null
        if (program.size !in 2..40) return null
        if (version == 0 && program.size != 20 && program.size != 32) return null
        val expected = if (version == 0) Variant.BECH32 else Variant.BECH32M
        if (decoded.variant != expected) return null
        return version to program
    }

    private const val MAX_SEGWIT_LENGTH = 90
}

// ---------------------------------------------------------------- CashAddr (Bitcoin Cash, Kaspa)

/**
 * CashAddr-style encoding: `prefix:payload` with a 40-bit BCH checksum over the lower 5 bits
 * of the prefix. Used by Bitcoin Cash and (with a different payload layout) Kaspa.
 */
object CashAddrCodec {
    private val GENERATORS = longArrayOf(0x98f2bc8e61L, 0x79b76d99e2L, 0xf33e5fb3c4L, 0xae2eabe2a8L, 0x1e4f43e470L)

    private fun polymod(values: ByteArray): Long {
        var c = 1L
        for (d in values) {
            val c0 = (c ushr 35).toInt()
            c = ((c and 0x07ffffffffL) shl 5) xor (d.toLong() and 0x1f)
            for (i in 0 until 5) if ((c0 ushr i) and 1 == 1) c = c xor GENERATORS[i]
        }
        return c xor 1L
    }

    private fun prefixBits(prefix: String) =
        ByteArray(prefix.length) { (prefix[it].code and 0x1f).toByte() } + byteArrayOf(0)

    fun encode(prefix: String, payload: ByteArray): String {
        val data = Bech32Codec.convertBits(payload, 8, 5, pad = true)!!
        val mod = polymod(prefixBits(prefix) + data + ByteArray(8))
        return buildString {
            append(prefix)
            append(':')
            data.forEach { append(Bech32Codec.CHARSET[it.toInt()]) }
            for (i in 0 until 8) append(Bech32Codec.CHARSET[((mod ushr (5 * (7 - i))) and 31).toInt()])
        }
    }

    /**
     * Decodes `prefix:payload` (or the bare payload, assuming [defaultPrefix]). Returns the
     * payload bytes or `null` when the checksum or padding is wrong.
     */
    fun decode(input: String, defaultPrefix: String): Pair<String, ByteArray>? {
        if (input.lowercase() != input && input.uppercase() != input) return null
        val text = input.lowercase()
        val prefix = if (':' in text) text.substringBefore(':') else defaultPrefix
        val body = text.substringAfter(':')
        if (body.length < 9) return null
        val data = Bech32Codec.toGroups(body) ?: return null
        if (polymod(prefixBits(prefix) + data) != 0L) return null
        val payload = Bech32Codec.convertBits(data.copyOfRange(0, data.size - 8), 5, 8, pad = false) ?: return null
        return prefix to payload
    }
}

// ---------------------------------------------------------------- Base32 (RFC 4648, no padding)

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
        val groups = Bech32Codec.convertBits(bytes, 8, 5, pad = true)!!
        return buildString { groups.forEach { append(ALPHABET[it.toInt()]) } }
    }

    /** Decodes upper-case RFC 4648 base32 without padding; `null` if malformed. */
    fun decode(text: String): ByteArray? {
        val groups = ByteArray(text.length)
        for (i in text.indices) {
            val value = ALPHABET.indexOf(text[i])
            if (value < 0) return null
            groups[i] = value.toByte()
        }
        // Trailing bits must be zero padding.
        return Bech32Codec.convertBits(groups, 5, 8, pad = false)
    }
}

// ---------------------------------------------------------------- Base64

/** RFC 4648 Base64, standard and URL-safe alphabets; padding optional when decoding. */
object Base64Codec {
    private const val STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray, urlSafe: Boolean = false): String {
        val alphabet = if (urlSafe) URL_SAFE else STANDARD
        val groups = Bech32Codec.convertBits(bytes, 8, 6, pad = true)!!
        val text = buildString { groups.forEach { append(alphabet[it.toInt()]) } }
        return text + "=".repeat((4 - text.length % 4) % 4)
    }

    /** Decodes either alphabet (not mixed); `null` if malformed. */
    fun decode(text: String): ByteArray? {
        val body = text.trimEnd('=')
        if (body.length % 4 == 1) return null
        val urlSafe = body.any { it == '-' || it == '_' }
        val alphabet = if (urlSafe) URL_SAFE else STANDARD
        val groups = ByteArray(body.length)
        for (i in body.indices) {
            val value = alphabet.indexOf(body[i])
            if (value < 0) return null
            groups[i] = value.toByte()
        }
        // Trailing padding bits of the last group must be zero.
        return Bech32Codec.convertBits(groups, 6, 8, pad = false)
    }
}

// ---------------------------------------------------------------- CRC16

object Crc16 {
    /** CRC-16/XMODEM (poly 0x1021, init 0): Stellar strkeys and TON user-friendly addresses. */
    fun xmodem(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
            crc = crc and 0xFFFF
        }
        return crc
    }
}

// ---------------------------------------------------------------- Base58Check variants

object Base58Checks {
    private const val BITCOIN_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** Decodes Base58 + 4-byte double-SHA256 checksum; returns the payload incl. prefix bytes. */
    fun decode(text: String, alphabet: String = BITCOIN_ALPHABET): ByteArray? {
        if (text.isEmpty()) return null
        val translated = if (alphabet == BITCOIN_ALPHABET) {
            text
        } else {
            buildString {
                text.forEach { c ->
                    val i = alphabet.indexOf(c)
                    if (i < 0) return null
                    append(BITCOIN_ALPHABET[i])
                }
            }
        }
        if (translated.any { it !in BITCOIN_ALPHABET }) return null
        val raw = runCatching { Base58.decode(translated) }.getOrNull() ?: return null
        if (raw.size < 5) return null
        val payload = raw.copyOfRange(0, raw.size - 4)
        val checksum = Crypto.hash256(payload).copyOfRange(0, 4)
        return if (checksum.contentEquals(raw.copyOfRange(raw.size - 4, raw.size))) payload else null
    }

    fun encode(payload: ByteArray, alphabet: String = BITCOIN_ALPHABET): String {
        val text = Base58.encode(payload + Crypto.hash256(payload).copyOfRange(0, 4))
        return if (alphabet ==
            BITCOIN_ALPHABET
        ) {
            text
        } else {
            text.map { alphabet[BITCOIN_ALPHABET.indexOf(it)] }.joinToString("")
        }
    }

    /** Plain Base58 decode (no checksum); `null` if malformed. */
    fun decodePlain(text: String): ByteArray? {
        if (text.isEmpty() || text.any { it !in BITCOIN_ALPHABET }) return null
        return runCatching { Base58.decode(text) }.getOrNull()
    }
}
