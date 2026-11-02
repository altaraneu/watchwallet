package com.altaran.watchwallet.core.security

/**
 * Decides whether free text may be sent to a coin search API. Coin names are short: anything that
 * looks like an address, a key or a sentence stays on the device.
 */
object SearchQueryGuard {
    private const val MAX_CHARS = 40
    private const val MAX_WORDS = 5
    private const val MAX_PLAIN_TOKEN_CHARS = 19
    private const val MIN_HEX_CHARS = 16
    private const val MIN_PREFIXED_HEX_CHARS = 6
    private const val MIN_DIGITS = 9
    private const val MIN_CODE_LIKE_CHARS = 16

    private val HEX = Regex("""^[0-9a-fA-F]+$""")
    private val PREFIXED_HEX = Regex("""^0[xX][0-9a-fA-F]+$""")
    private val DOTTED_NUMBERS = Regex("""^\d+\.\d+\.\d+$""")
    private val ACCOUNT_NAME = Regex("""^[\p{L}\d_-]+(\.[\p{L}\d_-]+)*\.(near|eth|sol|ton|tez|crypto|nft|x)$""", RegexOption.IGNORE_CASE)
    private val ALPHANUMERIC = Regex("""^[\p{L}\d]+$""")

    fun isAllowed(query: String): Boolean {
        if (SecretGuard.isSecret(query)) return false
        val text = SecretText.normalize(query).trim()
        if (text.length > MAX_CHARS) return false
        val words = text.split(' ', '\n').filter { it.isNotEmpty() }
        return words.size <= MAX_WORDS && words.none(::looksLikeCode)
    }

    private fun looksLikeCode(word: String): Boolean = when {
        HEX.matches(word) && word.length >= MIN_HEX_CHARS -> true

        PREFIXED_HEX.matches(word) && word.length >= MIN_PREFIXED_HEX_CHARS -> true

        word.all { it.isDigit() } && word.length >= MIN_DIGITS -> true

        DOTTED_NUMBERS.matches(word) || ACCOUNT_NAME.matches(word) -> true

        !ALPHANUMERIC.matches(word) -> false

        word.length > MAX_PLAIN_TOKEN_CHARS -> true

        // Base58 and bech32 fragments mix digits with letters; coin names like "1inch" are short.
        else -> word.length >= MIN_CODE_LIKE_CHARS && word.any { it.isDigit() }
    }
}
