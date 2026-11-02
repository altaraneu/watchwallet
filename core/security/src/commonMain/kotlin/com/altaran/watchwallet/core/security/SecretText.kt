package com.altaran.watchwallet.core.security

/**
 * Text normalisation for the secret guard, with explicit tables so that it behaves the same on the
 * JVM and on iOS: regular expression `\s` covers only ASCII on the JVM, and Kotlin's common library
 * has no Unicode normaliser.
 */
internal object SecretText {
    private const val ASCII_SPACE = ' '
    private const val LINE_FEED = '\n'
    private const val DOUBLE_QUOTE = '"'

    private const val FULLWIDTH_FIRST = 0xFF01
    private const val FULLWIDTH_LAST = 0xFF5E
    private const val FULLWIDTH_OFFSET = 0xFEE0

    /**
     * ASCII spaces and line breaks in place of every Unicode separator, ASCII in place of fullwidth
     * forms, invisible format characters removed so they cannot hide a key.
     */
    fun normalize(input: String): String = buildString(input.length) {
        for (char in input) {
            val code = char.code
            when {
                isInvisible(code) -> Unit
                isLineBreak(code) -> append(LINE_FEED)
                isSpace(code) -> append(ASCII_SPACE)
                isQuote(code) -> append(DOUBLE_QUOTE)
                code in FULLWIDTH_FIRST..FULLWIDTH_LAST -> append(Char(code - FULLWIDTH_OFFSET))
                else -> append(char)
            }
        }
    }

    private fun isLineBreak(code: Int) = code in 0x000A..0x000D || code == 0x0085 || code == 0x2028 || code == 0x2029

    /** Unicode category Zs, and the tab. */
    private fun isSpace(code: Int) = code == 0x0009 || code == 0x0020 || code == 0x00A0 || code == 0x1680 ||
        code in 0x2000..0x200A || code == 0x202F || code == 0x205F || code == 0x3000

    /** Zero-width characters, soft hyphen, bidirectional marks, word joiner, byte order mark. */
    private fun isInvisible(code: Int) = code == 0x00AD || code == 0x034F || code == 0x061C || code == 0x180E ||
        code in 0x200B..0x200F || code in 0x202A..0x202E || code in 0x2060..0x206F || code == 0xFEFF

    private fun isQuote(code: Int) = code in 0x2018..0x201F || code == 0x00AB || code == 0x00BB ||
        code == 0x2039 || code == 0x203A || code == 0x300C || code == 0x300D || code == 0x300E || code == 0x300F

    /** A letter, or a combining mark of one (vowel signs of Indic scripts are marks). */
    fun isWordChar(char: Char): Boolean = char.isLetter() || char.isSurrogate() || when (char.category) {
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> true
        else -> false
    }

    fun isHan(char: Char): Boolean = char.code in 0x4E00..0x9FFF || char.code in 0x3400..0x4DBF || char.code in 0xF900..0xFAFF

    /**
     * The comparison form of a word: lower case, Latin without diacritics, kana and Hangul composed.
     * The same word typed in NFC or NFD (or copied from an NFKD list) folds to the same string.
     */
    fun foldWord(word: String): String {
        val result = StringBuilder(word.length)
        for (char in word.lowercase()) {
            val code = char.code
            val last = result.lastOrNull()?.code
            when {
                code in 0x0300..0x036F -> {
                    Unit
                }

                (code == KANA_VOICED || code == KANA_SEMI_VOICED) && last != null && last in KANA -> {
                    result.setLength(result.length - 1)
                    result.append(Char(last + if (code == KANA_VOICED) 1 else 2))
                }

                code in HANGUL_VOWELS && last != null && last in HANGUL_LEADS -> {
                    val syllable = HANGUL_FIRST + ((last - HANGUL_LEADS.first) * HANGUL_VOWELS.count() + code - HANGUL_VOWELS.first) * HANGUL_TAIL_COUNT
                    result.setLength(result.length - 1)
                    result.append(Char(syllable))
                }

                code in HANGUL_TAILS && last != null && isOpenSyllable(last) -> {
                    result.setLength(result.length - 1)
                    result.append(Char(last + code - HANGUL_TAILS.first + 1))
                }

                else -> {
                    result.append(foldLatin(char))
                }
            }
        }
        return result.toString()
    }

    private fun isOpenSyllable(code: Int) = code in HANGUL_FIRST..HANGUL_LAST && (code - HANGUL_FIRST) % HANGUL_TAIL_COUNT == 0

    private fun foldLatin(char: Char): Char {
        if (char.code < LATIN_FOLD_FIRST || char.code >= LATIN_FOLD_END) return char
        val index = LATIN_FROM.indexOf(char)
        return if (index >= 0) LATIN_TO[index] else char
    }

    private const val KANA_VOICED = 0x3099
    private const val KANA_SEMI_VOICED = 0x309A
    private val KANA = 0x3041..0x30FF
    private val HANGUL_LEADS = 0x1100..0x1112
    private val HANGUL_VOWELS = 0x1161..0x1175
    private val HANGUL_TAILS = 0x11A8..0x11C2
    private const val HANGUL_TAIL_COUNT = 28
    private const val HANGUL_FIRST = 0xAC00
    private const val HANGUL_LAST = 0xD7A3
    private const val LATIN_FOLD_FIRST = 0x00C0
    private const val LATIN_FOLD_END = 0x0250
    private const val LATIN_FROM =
        "àáâãäåçèéêëìíîïñòóôõöùúûüýÿāăąćĉċčďēĕėęěĝğġģĥĩīĭįĵķĺļľńņňōŏőŕŗřśŝşšţťũūŭůűųŵŷźżžơưǎǐǒǔǖǘǚǜǟǡǧǩǫǭǰǵǹǻȁȃȅȇȉȋȍȏȑȓȕȗșțȟȧȩȫȭȯȱȳ"
    private const val LATIN_TO =
        "aaaaaaceeeeiiiinooooouuuuyyaaaccccdeeeeegggghiiiijklllnnnooorrrssssttuuuuuuwyzzzouaiouuuuuaagkoojgnaaaeeiioorruusthaeooooy"
}
