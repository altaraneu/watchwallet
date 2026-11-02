package com.altaran.watchwallet.core.security

import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.Base58Check

/** Kinds of key material the app refuses. Used to explain the rejection to the user. */
enum class SecretKind {
    /** BIP39 / Electrum / Algorand / Monero style word list, or the same as a SeedQR. */
    MNEMONIC,

    /** `xprv`, `yprv`, `zprv`, `tprv`, `Ltpv`, `dgpv`… (also inside output descriptors). */
    EXTENDED_PRIVATE_KEY,

    /** Wallet Import Format private key (Bitcoin, Litecoin, Dogecoin…), BIP38 or mini key. */
    WIF_PRIVATE_KEY,

    /** Raw hexadecimal private key or seed (EVM, Bitcoin, Monero spend/view keys, NEAR, Hedera…). */
    HEX_PRIVATE_KEY,

    /** Solana secret key: a byte array of 32 or 64 numbers, or 64 bytes in base58. */
    SOLANA_SECRET_KEY,

    /** XRP Ledger family seed (`s…`). */
    XRP_SEED,

    /** Stellar secret seed (`S…`). */
    STELLAR_SECRET_SEED,

    /** Cardano signing keys (`acct_xsk…`, `root_xsk…`, `ed25519_sk…`…). */
    CARDANO_SIGNING_KEY,

    /** Sui Bech32 private key (`suiprivkey1…`). */
    SUI_PRIVATE_KEY,

    /** Aptos AIP-80 private key (`ed25519-priv-0x…`, `secp256k1-priv-0x…`). */
    APTOS_PRIVATE_KEY,

    /** Tezos secret key (`edsk…`, `spsk…`, `p2sk…`). */
    TEZOS_SECRET_KEY,

    /** NEAR private key (`ed25519:` + 64 bytes). */
    NEAR_PRIVATE_KEY,

    /** Encrypted JSON keystore (Ethereum, Polkadot.js…) or an ASCII-armored key export. */
    ENCRYPTED_KEYSTORE,

    /** Base64-encoded private key (Algorand `sk`, legacy Sui keystore entries…). */
    BASE64_PRIVATE_KEY,
}

/**
 * Thrown when something that looks like key material reaches persistence. Carries only the kind:
 * the text is never part of a message, a log or a crash report. Callers show the usual warning.
 */
class SecretRejectedException(val kind: SecretKind) : IllegalArgumentException("Refusing to store key material")

sealed interface SecretCheck {
    data object Clean : SecretCheck

    data class Secret(val kind: SecretKind) : SecretCheck
}

/**
 * Detects seed phrases and private keys in user input, **before** the input is classified,
 * probed over the network, persisted or logged.
 *
 * Design rules:
 * - Fail closed: when in doubt, treat input as a secret. A rejected address can be re-entered;
 *   a leaked key cannot be un-leaked.
 * - Every token of the input is checked, so a key hidden in a descriptor, a CSV row or a
 *   payment URI is still caught.
 * - Pure and offline. The input is never stored or logged here.
 *
 * `0x` + 64 hex characters is rejected like any hex key, although Sui and Aptos addresses have the
 * same form: only [checkDeliberateAddress], the deliberately chosen route for those two chains,
 * lets it through.
 *
 * What no check can do: a 32-byte Solana secret in base58 is the same form as a Solana address, and
 * a raw key behind that route is the same form as a Sui or Aptos address.
 */
object SecretGuard {
    fun check(input: String): SecretCheck {
        val text = SecretText.normalize(input).trim()
        if (text.isEmpty()) return SecretCheck.Clean
        if (MnemonicDetector.detect(text)) return SecretCheck.Secret(SecretKind.MNEMONIC)
        return keyMaterial(text)
    }

    /**
     * For the route where the user explicitly chose Sui or Aptos: the value `0x` + 64 hex alone
     * passes. Anything else, or anything next to it, is judged by [check].
     */
    fun checkDeliberateAddress(input: String): SecretCheck =
        if (isHexAddressForm(input)) SecretCheck.Clean else check(input)

    /** `0x` + 64 hex characters: a Sui or Aptos address, or the private key of many chains. */
    fun isHexAddressForm(input: String): Boolean = PREFIXED_HEX_64.matches(SecretText.normalize(input).trim())

    /**
     * Keys and key files only, without the search for word lists. For structured files whose field
     * names read like words and whose values are each passed to [check].
     */
    fun checkKeyMaterial(input: String): SecretCheck {
        val text = SecretText.normalize(input).trim()
        return if (text.isEmpty()) SecretCheck.Clean else keyMaterial(text)
    }

    /**
     * A QR code with raw bytes instead of text. Compact SeedQR is the bare entropy of 12 to 24 words.
     * Text that merely has one of these lengths passes on to the normal checks.
     */
    fun checkBinary(bytes: ByteArray): SecretCheck =
        if (bytes.size in BINARY_SEED_SIZES && !isText(bytes)) SecretCheck.Secret(SecretKind.MNEMONIC) else SecretCheck.Clean

    fun isSecret(input: String): Boolean = check(input) is SecretCheck.Secret

    private const val LOWEST_TEXT_BYTE = 0x20
    private const val DELETE_BYTE = 0x7F
    private val BINARY_SEED_SIZES = setOf(16, 20, 24, 28, 32)

    private fun isText(bytes: ByteArray): Boolean {
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return false
        return text.none { (it.code < LOWEST_TEXT_BYTE && it != '\n' && it != '\r' && it != '\t') || it.code == DELETE_BYTE }
    }

    private fun keyMaterial(text: String): SecretCheck {
        wholeInputSecret(text)?.let { return SecretCheck.Secret(it) }
        for (token in tokenize(text)) {
            tokenSecret(token)?.let { return SecretCheck.Secret(it) }
        }
        return SecretCheck.Clean
    }

    // ---------------------------------------------------------------- whole input

    private val WHITESPACE_RUN = Regex("""[ \n]+""")
    private val WORD_SEPARATORS = Regex("""[ \n,;]+""")
    private val KEYSTORE_MARKERS = listOf("ciphertext", "\"kdf\"", "cipherparams")
    private val POLKADOT_KEYSTORE_MARKERS = listOf("\"encoded\"", "\"encoding\"")
    private val ARMORED_KEY = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----""")
    private val BYTE_LIST_SEPARATORS = Regex("""[ \n,;\[\]"]+""")
    private val HEX_BYTE = Regex("""^0[xX][0-9a-fA-F]{1,2}$""")
    private val PREFIXED_HEX_64 = Regex("""^0[xX][0-9a-fA-F]{64}$""")
    private val SPACED_HEX_SEPARATORS = Regex("""[ \n:,-]+""")
    private const val MIN_KEY_BYTES_IN_LIST = 32
    private const val HIGHEST_BYTE = 255
    private const val MAX_BYTE_DIGITS = 3

    private fun wholeInputSecret(text: String): SecretKind? {
        // Solana CLI keypair file and similar: 32 or 64 numbers, also inside longer text.
        byteList(text)?.let { return it }
        // SeedQR digits, possibly grouped.
        if (isSeedQrDigits(text.replace(WHITESPACE_RUN, ""))) return SecretKind.MNEMONIC
        spacedHexKey(text)?.let { return it }
        if (KEYSTORE_MARKERS.all { text.contains(it, ignoreCase = true) }) return SecretKind.ENCRYPTED_KEYSTORE
        // Base64 keys contain `/`, `+` and `=`, which the token splitter treats as separators.
        if (text.split(WORD_SEPARATORS).any(::isBase64Key)) return SecretKind.BASE64_PRIVATE_KEY
        if (POLKADOT_KEYSTORE_MARKERS.all { text.contains(it) }) return SecretKind.ENCRYPTED_KEYSTORE
        if (ARMORED_KEY.containsMatchIn(text)) return SecretKind.ENCRYPTED_KEYSTORE
        return null
    }

    private fun byteList(text: String): SecretKind? {
        var decimal = 0
        var hex = 0
        for (token in text.split(BYTE_LIST_SEPARATORS)) {
            if (token.isEmpty()) continue
            val isByte = token.length <= MAX_BYTE_DIGITS && token.all { it.isAsciiDigit() } && token.toInt() <= HIGHEST_BYTE
            decimal = if (isByte) decimal + 1 else 0
            hex = if (HEX_BYTE.matches(token)) hex + 1 else 0
            if (decimal >= MIN_KEY_BYTES_IN_LIST) return SecretKind.SOLANA_SECRET_KEY
            if (hex >= MIN_KEY_BYTES_IN_LIST) return SecretKind.HEX_PRIVATE_KEY
        }
        return null
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** A hex key broken up by spaces, colons, hyphens or line breaks. */
    private fun spacedHexKey(text: String): SecretKind? {
        val joined = text.removePrefix("0x").removePrefix("0X").replace(SPACED_HEX_SEPARATORS, "")
        return if (joined.length >= MIN_HEX_KEY_CHARS && HEX.matches(joined)) SecretKind.HEX_PRIVATE_KEY else null
    }

    private val SEED_QR_DIGITS = setOf(48, 60, 72, 84, 96)
    private const val SEED_QR_GROUP = 4
    private const val HIGHEST_WORD_INDEX = 2047

    /** Standard SeedQR: the word indexes of a phrase as four digits each. */
    private fun isSeedQrDigits(value: String): Boolean = value.length in SEED_QR_DIGITS &&
        value.all { it.isAsciiDigit() } &&
        value.chunked(SEED_QR_GROUP).all { it.toInt() <= HIGHEST_WORD_INDEX }

    // ---------------------------------------------------------------- tokens

    private val TOKEN_SEPARATORS = Regex("""[ \n,;()\[\]{}"'<>=?&|\\]+""")

    private fun tokenize(text: String): List<String> =
        text.split(TOKEN_SEPARATORS)
            .map { it.trim().trimEnd('/', '.', ':') }
            .flatMap { token ->
                // "bitcoin:5Kb8..." or "wpkh(xprv.../0/*)" → also check the part after a scheme or slash.
                listOf(token) + token.split(':', '/').filter { it.length >= 20 }
            }
            .filter { it.length >= 20 }
            .distinct()

    private val EXTENDED_PRIVATE_PREFIXES =
        listOf("xprv", "yprv", "zprv", "tprv", "uprv", "vprv", "Yprv", "Zprv", "Uprv", "Vprv", "Ltpv", "Mtpv", "dgpv", "kprv")

    /** Versions of extended **public** keys; an unknown version of this form is refused. */
    private val EXTENDED_PUBLIC_VERSIONS = setOf(
        0x0488B21E, // xpub
        0x049D7CB2, // ypub
        0x04B24746, // zpub
        0x0295B43F, // Ypub
        0x02AA7ED3, // Zpub
        0x043587CF, // tpub
        0x044A5262, // upub
        0x045F1CF6, // vpub
        0x024289EF, // Upub
        0x02575483, // Vpub
        0x019DA462, // Ltub
        0x01B26EF6, // Mtub
        0x02FACAFD, // dgub
        0x038F332E, // kpub
    )
    private const val EXTENDED_KEY_BYTES = 78
    private const val CHECKSUM_BYTES = 4
    private const val EXTENDED_KEY_OFFSET = 45
    private const val BYTE_MASK = 0xFF
    private const val VERSION_BYTES = 4
    private const val BITS_PER_BYTE = 8
    private val HEX = Regex("""^[0-9a-fA-F]+$""")
    private val BASE58 = Regex("""^[1-9A-HJ-NP-Za-km-z]+$""")
    private val XRP_SEED = Regex("""^s[rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz]{28,30}$""")
    private val STELLAR_SEED = Regex("""^S[A-Z2-7]{55}$""")
    private val CARDANO_SIGNING = Regex("""^[a-z_]*(_sk|_xsk|xsk|_xprv)1[02-9ac-hj-np-z]+$""")
    private val TEZOS_SECRET = Regex("""^(edsk|spsk|p2sk|edesk|spesk|p2esk)[1-9A-HJ-NP-Za-km-z]{40,}$""")
    private val BIP38 = Regex("""^6P[1-9A-HJ-NP-Za-km-z]{56}$""")
    private val MINI_KEY = Regex("""^S[1-9A-HJ-NP-Za-km-z]{21}$|^S[1-9A-HJ-NP-Za-km-z]{29}$""")
    private const val PRIVATE_KEY_BYTES = 32
    private const val KEYPAIR_BYTES = 64
    private const val MIN_HEX_KEY_CHARS = 64

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun tokenSecret(token: String): SecretKind? {
        val lower = token.lowercase()

        if (EXTENDED_PRIVATE_PREFIXES.any { token.startsWith(it) } && token.length >= 100) {
            return SecretKind.EXTENDED_PRIVATE_KEY
        }
        if (lower.startsWith("suiprivkey1")) return SecretKind.SUI_PRIVATE_KEY
        if (lower.startsWith("ed25519-priv-0x") || lower.startsWith("secp256k1-priv-0x")) {
            return SecretKind.APTOS_PRIVATE_KEY
        }
        if (CARDANO_SIGNING.matches(lower)) return SecretKind.CARDANO_SIGNING_KEY
        if (lower.startsWith("ed25519:") && base58Length(token.substringAfter(':')) == KEYPAIR_BYTES) {
            return SecretKind.NEAR_PRIVATE_KEY
        }
        if (lower.startsWith("secp256k1:") && base58Length(token.substringAfter(':')) in PRIVATE_KEY_BYTES..KEYPAIR_BYTES) {
            return SecretKind.NEAR_PRIVATE_KEY
        }
        if (TEZOS_SECRET.matches(token)) return SecretKind.TEZOS_SECRET_KEY
        if (STELLAR_SEED.matches(token)) return SecretKind.STELLAR_SECRET_SEED
        if (XRP_SEED.matches(token)) return SecretKind.XRP_SEED
        if (isSeedQrDigits(token)) return SecretKind.MNEMONIC

        // Hex: 64+ hex characters are always refused, with or without 0x. That covers NEAR implicit
        // accounts and, through checkDeliberateAddress only, Sui and Aptos addresses.
        val hexBody = token.removePrefix("0x").removePrefix("0X")
        if (HEX.matches(hexBody) && hexBody.length >= MIN_HEX_KEY_CHARS) return SecretKind.HEX_PRIVATE_KEY

        if (BASE58.matches(token)) {
            if (BIP38.matches(token) || MINI_KEY.matches(token)) return SecretKind.WIF_PRIVATE_KEY
            if (isWif(token)) return SecretKind.WIF_PRIVATE_KEY
            if (isExtendedSecret(token)) return SecretKind.EXTENDED_PRIVATE_KEY
            if (base58Length(token) == KEYPAIR_BYTES) return SecretKind.SOLANA_SECRET_KEY
        }
        return null
    }

    /**
     * BIP-32 serialised keys, whatever their prefix: the byte at the key offset is 0x00 for a private
     * key. With a valid checksum, any version that is not a known public one is refused too.
     */
    private fun isExtendedSecret(token: String): Boolean {
        val raw = runCatching { Base58.decode(token) }.getOrNull() ?: return false
        if (raw.size != EXTENDED_KEY_BYTES + CHECKSUM_BYTES) return false
        if (raw[EXTENDED_KEY_OFFSET].toInt() == 0) return true
        if (runCatching { Base58Check.decode(token) }.isFailure) return false
        val version = (0 until VERSION_BYTES).fold(0) { value, i -> (value shl BITS_PER_BYTE) or (raw[i].toInt() and BYTE_MASK) }
        return version !in EXTENDED_PUBLIC_VERSIONS
    }

    /** WIF: Base58Check with a 1-byte version and 32 bytes, or 33 bytes ending in 0x01 (compressed). */
    private fun isWif(token: String): Boolean {
        val payload = runCatching { Base58Check.decode(token).second }.getOrNull() ?: return false
        return payload.size == PRIVATE_KEY_BYTES ||
            (payload.size == PRIVATE_KEY_BYTES + 1 && payload.last() == 0x01.toByte())
    }

    private val BASE64 = Regex("""^[A-Za-z0-9+/]+={0,2}$""")

    /**
     * Base64 that decodes to a typical key length. It must contain `+`, `/` or `=`: base58
     * addresses (Solana, Tron…) never do, so they are not mistaken for keys.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun isBase64Key(token: String): Boolean {
        if (!BASE64.matches(token) || token.none { it == '+' || it == '/' || it == '=' }) return false
        val size = runCatching { kotlin.io.encoding.Base64.decode(token).size }.getOrDefault(-1)
        return size == PRIVATE_KEY_BYTES || size == PRIVATE_KEY_BYTES + 1 || size == KEYPAIR_BYTES
    }

    private fun base58Length(value: String): Int =
        if (BASE58.matches(value)) runCatching { Base58.decode(value).size }.getOrDefault(-1) else -1
}
