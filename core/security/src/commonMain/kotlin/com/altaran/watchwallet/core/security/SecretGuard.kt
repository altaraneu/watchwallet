package com.altaran.watchwallet.core.security

import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.Base58Check

/** Kinds of key material the app refuses. Used to explain the rejection to the user. */
enum class SecretKind {
    /** BIP39 / Electrum / Algorand / Monero style word list. */
    MNEMONIC,

    /** `xprv`, `yprv`, `zprv`, `tprv`, `Ltpv`, `dgpv`… (also inside output descriptors). */
    EXTENDED_PRIVATE_KEY,

    /** Wallet Import Format private key (Bitcoin, Litecoin, Dogecoin…), BIP38 or mini key. */
    WIF_PRIVATE_KEY,

    /** Raw hexadecimal private key or seed (EVM, Bitcoin, Monero spend/view keys, NEAR, Hedera…). */
    HEX_PRIVATE_KEY,

    /** Solana 64-byte secret key as a JSON byte array or base58. */
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
 * Deliberately **not** treated as secrets (they are valid addresses and handled by an explicit
 * confirmation flow in the classifier): `0x` + 64 hex characters (Sui / Aptos addresses).
 */
object SecretGuard {
    fun check(input: String): SecretCheck {
        val text = input.trim()
        if (text.isEmpty()) return SecretCheck.Clean

        wholeInputSecret(text)?.let { return SecretCheck.Secret(it) }
        for (token in tokenize(text)) {
            tokenSecret(token)?.let { return SecretCheck.Secret(it) }
        }
        return SecretCheck.Clean
    }

    fun isSecret(input: String): Boolean = check(input) is SecretCheck.Secret

    // ---------------------------------------------------------------- whole input

    private val WORD = Regex("""^\p{L}{2,12}$""")
    private val MNEMONIC_MIN_WORDS = 12
    private val MNEMONIC_WORD_RATIO = 0.9
    private val JSON_BYTE_ARRAY = Regex("""^\[\s*\d{1,3}(\s*,\s*\d{1,3}){63}\s*]$""")
    private val KEYSTORE_MARKERS = listOf("ciphertext", "\"kdf\"", "cipherparams")
    private val POLKADOT_KEYSTORE_MARKERS = listOf("\"encoded\"", "\"encoding\"")
    private val ARMORED_KEY = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----""")

    private fun wholeInputSecret(text: String): SecretKind? {
        // Word lists: 12+ alphabetic words (any language, any separator).
        val words = text.split(Regex("""[\s,;]+""")).filter { it.isNotEmpty() }
        if (words.size >= MNEMONIC_MIN_WORDS) {
            val alphabetic = words.count { WORD.matches(it) }
            if (alphabetic >= words.size * MNEMONIC_WORD_RATIO) return SecretKind.MNEMONIC
        }
        // Solana CLI keypair file: [12,34,...] with 64 bytes.
        if (JSON_BYTE_ARRAY.containsMatchIn(text.replace(Regex("""\s+"""), ""))) return SecretKind.SOLANA_SECRET_KEY
        if (KEYSTORE_MARKERS.all { text.contains(it, ignoreCase = true) }) return SecretKind.ENCRYPTED_KEYSTORE
        // Base64 keys contain `/`, `+` and `=`, which the token splitter treats as separators.
        if (words.any(::isBase64Key)) return SecretKind.BASE64_PRIVATE_KEY
        if (POLKADOT_KEYSTORE_MARKERS.all { text.contains(it) }) return SecretKind.ENCRYPTED_KEYSTORE
        if (ARMORED_KEY.containsMatchIn(text)) return SecretKind.ENCRYPTED_KEYSTORE
        return null
    }

    // ---------------------------------------------------------------- tokens

    private val TOKEN_SEPARATORS = Regex("""[\s,;()\[\]{}"'<>=?&|\\]+""")

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

        // Hex: bare 64+ hex characters are always refused (also covers NEAR implicit accounts,
        // which the user can add through an explicit, confirmed flow). 0x + exactly 64 is an
        // address format (Sui / Aptos) and handled by the classifier's confirmation flow.
        val hexBody = token.removePrefix("0x").removePrefix("0X")
        if (HEX.matches(hexBody) && hexBody.length >= MIN_HEX_KEY_CHARS) {
            val hasPrefix = hexBody.length != token.length
            if (!hasPrefix || hexBody.length != MIN_HEX_KEY_CHARS) return SecretKind.HEX_PRIVATE_KEY
        }

        if (BASE58.matches(token)) {
            if (BIP38.matches(token) || MINI_KEY.matches(token)) return SecretKind.WIF_PRIVATE_KEY
            if (isWif(token)) return SecretKind.WIF_PRIVATE_KEY
            if (base58Length(token) == KEYPAIR_BYTES) return SecretKind.SOLANA_SECRET_KEY
        }
        return null
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
