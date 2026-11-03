package com.altaran.watchwallet.core.security

import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.Crypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The findings of the v1.0.0 audit, one row per bypass. Every sample is a published test vector or
 * an obviously fake value; none has ever held funds.
 */
class SecretGuardAuditFindingsTest {
    private val abandon = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val legal = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(' ')
    private val hexKey = "0c28fca386c7a227600b2fe50b7cae11ec86d3bf1fbe471be89827e19d72aa1d"

    private fun secret(input: String, name: String) =
        assertIs<SecretCheck.Secret>(SecretGuard.check(input), "$name must be rejected")

    @Test
    fun prefixedHexOf64CharactersIsAPrivateKeyByDefault() {
        listOf(
            "0x$hexKey",
            "0X$hexKey",
            "0x${hexKey.uppercase()}",
            "my key 0x$hexKey",
            "ethereum:0x$hexKey",
        ).forEach { assertEquals(SecretCheck.Secret(SecretKind.HEX_PRIVATE_KEY), SecretGuard.check(it), it) }
    }

    @Test
    fun theDeliberateRouteAcceptsOnlyTheAddressForm() {
        assertEquals(SecretCheck.Clean, SecretGuard.checkDeliberateAddress("0x$hexKey"))
        assertEquals(SecretCheck.Clean, SecretGuard.checkDeliberateAddress("  0x$hexKey\n"))
        // Everything else stays rejected, also on that route.
        assertIs<SecretCheck.Secret>(SecretGuard.checkDeliberateAddress(hexKey))
        assertIs<SecretCheck.Secret>(SecretGuard.checkDeliberateAddress("0x$hexKey$hexKey"))
        assertIs<SecretCheck.Secret>(SecretGuard.checkDeliberateAddress("note 0x$hexKey"))
        assertIs<SecretCheck.Secret>(SecretGuard.checkDeliberateAddress(abandon))
    }

    @Test
    fun englishMnemonicsAreCaughtInEveryFormatting() {
        val w = legal
        mapOf(
            "numbered dots" to w.mapIndexed { i, x -> "${i + 1}. $x" }.joinToString(" "),
            "numbered parens" to w.mapIndexed { i, x -> "${i + 1}) $x" }.joinToString(" "),
            "numbered per line" to w.mapIndexed { i, x -> "${i + 1} $x" }.joinToString("\n"),
            "number on its own line" to w.mapIndexed { i, x -> "${i + 1}\n$x" }.joinToString("\n"),
            "numbered no space" to w.mapIndexed { i, x -> "${i + 1}.$x" }.joinToString(" "),
            "zero padded" to w.mapIndexed { i, x -> "${(i + 1).toString().padStart(2, '0')}: $x" }.joinToString("\n"),
            "ideographic space" to w.joinToString(Char(0x3000).toString()),
            "non-breaking space" to w.joinToString(Char(0x00A0).toString()),
            "narrow no-break space" to w.joinToString(Char(0x202F).toString()),
            "thin space" to w.joinToString(Char(0x2009).toString()),
            "zero width space" to w.joinToString(Char(0x200B).toString()),
            "hyphens" to w.joinToString("-"),
            "underscores" to w.joinToString("_"),
            "dots" to w.joinToString("."),
            "slashes" to w.joinToString("/"),
            "pipes" to w.joinToString("|"),
            "double quotes" to w.joinToString(" ") { "\"$it\"" },
            "curly quotes" to w.joinToString(" ") { "${Char(0x201C)}$it${Char(0x201D)}" },
            "JSON array" to w.joinToString(",", "[", "]") { "\"$it\"" },
            "JSON object" to """{"mnemonic":"${w.joinToString(" ")}"}""",
            "run together" to w.joinToString(""),
            "camel case" to w.joinToString("") { x -> x.replaceFirstChar { it.uppercase() } },
            "upper case" to w.joinToString(" ").uppercase(),
            "fullwidth" to w.joinToString(" ") { word -> word.map { Char(it.code + 0xFEE0) }.joinToString("") },
            "inside a note" to "my seed is ${w.joinToString(" ")} do not lose it",
            "eleven words" to w.dropLast(1).joinToString(" "),
            "two typos" to (listOf("legl", "winer") + w.drop(2)).joinToString(" "),
            "three unknown words" to (listOf("qqq", "www", "eee") + w.drop(3)).joinToString(" "),
            "first four letters only" to w.joinToString(" ") { it.take(4) },
        ).forEach { (name, input) -> secret(input, name) }
    }

    @Test
    fun otherLanguagesAreCaught() {
        val space = Char(0x3000).toString()
        val japanese = List(11) { "あいこくしん" } + "あおぞら"
        mapOf(
            "Japanese with ideographic spaces" to japanese.joinToString(space),
            "Japanese numbered" to japanese.mapIndexed { i, x -> "${i + 1}. $x" }.joinToString("\n"),
            "Chinese separated" to "的 的 的 的 的 的 的 的 的 的 的 在",
            "Chinese without separators" to "的的的的的的的的的的的在",
            "Chinese comma separated" to "的、的、的、的、的、的、的、的、的、的、的、在",
            "Czech" to List(12) { "abeceda" }.joinToString(" "),
        ).forEach { (name, input) -> secret(input, name) }
    }

    @Test
    fun byteArraysAreCaughtAlsoInsideOtherText() {
        val bytes64 = List(64) { (it * 3) % 256 }
        val bytes32 = bytes64.take(32)
        listOf(
            bytes32.joinToString(",", "[", "]"),
            bytes32.joinToString(", ", "[ ", " ]"),
            bytes64.joinToString(",", "[", "]"),
            "my solana key: ${bytes64.joinToString(",", "[", "]")} keep it safe",
            "key = ${bytes32.joinToString(",", "[", "]")}",
            bytes32.joinToString(" "),
            bytes32.joinToString(",") { "0x" + it.toString(16).padStart(2, '0') },
        ).forEach { secret(it, it) }
        // Short lists of numbers are not keys.
        assertEquals(SecretCheck.Clean, SecretGuard.check(List(16) { it }.joinToString(",", "[", "]")))
    }

    /** Base58Check of a 78-byte BIP32 node with the given version and key prefix byte. */
    private fun bip32(version: Int, keyPrefix: Int): String {
        val payload = ByteArray(BIP32_SIZE) { (it % 200 + 7).toByte() }
        payload[0] = (version ushr 24).toByte()
        payload[1] = (version ushr 16).toByte()
        payload[2] = (version ushr 8).toByte()
        payload[3] = version.toByte()
        payload[KEY_OFFSET] = keyPrefix.toByte()
        val digest = Crypto.hash256(payload)
        return Base58.encode(payload + digest.copyOfRange(0, 4))
    }

    @Test
    fun extendedKeysAreJudgedByTheirContentNotTheirPrefix() {
        // A private key under an unknown version, and under a public version.
        secret(bip32(0x12345678, 0x00), "unknown version with private key")
        secret(bip32(0x0488B21E, 0x00), "xpub version with private key")
        // Unknown versions stay rejected even with a public-looking key: fail closed.
        secret(bip32(0x12345678, 0x02), "unknown version")
        // Real public keys keep working.
        assertEquals(SecretCheck.Clean, SecretGuard.check(bip32(0x0488B21E, 0x02)))
        assertEquals(SecretCheck.Clean, SecretGuard.check(bip32(0x04B24746, 0x03)))
        assertEquals(SecretCheck.Clean, SecretGuard.check(bip32(0x019DA462, 0x03)))
        assertEquals(SecretCheck.Clean, SecretGuard.check(bip32(0x02FACAFD, 0x02)))
    }

    @Test
    fun seedQrDigitsAreRejected() {
        // Word indexes as four digits: 12, 15, 18, 21 and 24 words.
        listOf(12, 15, 18, 21, 24).forEach { words ->
            secret(List(words) { (it * 137 % 2048).toString().padStart(4, '0') }.joinToString(""), "SeedQR of $words words")
        }
        secret(List(12) { (it * 137 % 2048).toString().padStart(4, '0') }.joinToString(" "), "SeedQR with spaces")
        // A group above 2047 is not a word index; another length is not a SeedQR.
        assertEquals(SecretCheck.Clean, SecretGuard.check("9".repeat(48)))
        assertEquals(SecretCheck.Clean, SecretGuard.check("1".repeat(47)))
        assertEquals(SecretCheck.Clean, SecretGuard.check("0.0.1234567"))
    }

    @Test
    fun binaryQrPayloadsOfSeedLengthsAreRejected() {
        listOf(16, 20, 24, 28, 32).forEach { size ->
            assertIs<SecretCheck.Secret>(SecretGuard.checkBinary(ByteArray(size) { (it * 11 + 128).toByte() }), "$size bytes")
        }
        assertEquals(SecretCheck.Clean, SecretGuard.checkBinary("bc1qar0srrr7xfkvy5l6".encodeToByteArray()))
        assertEquals(SecretCheck.Clean, SecretGuard.checkBinary(ByteArray(17) { (it * 11 + 128).toByte() }))
        assertEquals(SecretCheck.Clean, SecretGuard.checkBinary(ByteArray(0)))
    }

    @Test
    fun invisibleCharactersDoNotHideKeys() {
        val wif = "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ"
        val zeroWidth = Char(0x200B)
        secret(wif.take(10) + zeroWidth + wif.drop(10), "zero width in WIF")
        secret(Char(0x00A0) + wif + Char(0x3000), "unicode spaces around WIF")
        secret("0x" + hexKey.chunked(2).joinToString(" "), "spaced hex")
        secret(hexKey.chunked(2).joinToString(":"), "colon hex")
        secret(hexKey.chunked(32).joinToString("\n"), "hex over two lines")
        secret(hexKey.map { Char(it.code + 0xFEE0) }.joinToString(""), "fullwidth hex")
    }

    @Test
    fun hexKeysThatLostLeadingZerosAreRejected() {
        secret(hexKey.drop(1), "63 characters")
        secret("0x" + hexKey.drop(4), "60 characters with prefix")
        assertEquals(SecretCheck.Clean, SecretGuard.check("0x" + hexKey.take(40)), "EVM address")
    }

    @Test
    fun wordsJoinedByTheirNumbersAreCaught() {
        val words = "abandon ability able about above absent absorb abstract absurd abuse access accident".split(' ')
        secret(words.mapIndexed { i, word -> "${i + 1}$word" }.joinToString(""), "numbers before words")
        secret(words.mapIndexed { i, word -> "$word${i + 1}" }.joinToString(""), "numbers after words")
        assertEquals(SecretCheck.Clean, SecretGuard.check("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"), "address with digits")
    }

    @Test
    fun base64KeysWithoutSymbolsAreRejected() {
        // Legacy Sui keystore form: flag byte and 32 key bytes, 44 characters, none of + / =.
        val symbolFree33Bytes = "AL" + "0Oa1Il".repeat(7)
        secret(symbolFree33Bytes, "33 bytes without symbols")
        secret("note $symbolFree33Bytes", "inside longer text")
        secret("b0Oa1IlZ".repeat(5) + "b0O", "32 bytes without padding")
        secret("b0Oa1IlZ".repeat(10) + "b0Oa1I", "64 bytes without padding")
        secret("b0Oa-Il_".repeat(5) + "b0O", "url-safe alphabet")
    }

    @Test
    fun addressesOfTheSameLengthAreNotMistakenForBase64Keys() {
        listOf(
            "So11111111111111111111111111111111111111112",
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            "ltc1qw508d6qejxtdg4y5r3zarvary0c5xw7kgmn4n9",
            "osmo1qypqxpq9qcrsszg2pvxq6rs0zqg3yyc5lzv7xu",
        ).forEach { assertEquals(SecretCheck.Clean, SecretGuard.check(it), it) }
    }

    private companion object {
        const val BIP32_SIZE = 78
        const val KEY_OFFSET = 45
    }
}
