package com.altaran.watchwallet.core.security

/**
 * Finds seed phrases in normalised text ([SecretText.normalize]).
 *
 * Long notes are judged by hits in the BIP-39 lists, never by word count alone: a normal sentence of
 * twelve words must pass, because its function words ("the", "pro", "de") are not on any list. Hits
 * count per language, since a phrase uses one list.
 *
 * Only an exact seed length of plain words with no sign of a sentence is rejected without list hits
 * ([looksLikeUnknownWordList]), for Monero, old Electrum, SLIP-39 and unofficial translated lists.
 */
internal object MnemonicDetector {
    private const val LANGUAGES = 10
    private const val CHINESE_SIMPLIFIED = 4
    private const val CHINESE_TRADITIONAL = 5

    /** First language index of the lists written in an alphabet, for prefix matching. */
    private val ALPHABETIC = listOf(0, 3, 6, 7, 8, 9)

    private const val MIN_WORDS = 11
    private const val WINDOW = 12

    /** Three out of four words on one list ("most"), which leaves room for typos. */
    private const val MIN_EXACT_RATIO = 0.75

    /** Words typed with typos or cut to four letters still share the prefix, which BIP-39 keeps unique. */
    private const val PREFIX_LENGTH = 4

    private const val MAX_WORD_LENGTH = 12
    private const val MIN_RUN_LETTERS = 22
    private val SEED_LENGTHS = setOf(12, 13, 15, 18, 20, 21, 24, 25, 33)
    private val HAN_SEED_LENGTHS = setOf(12, 15, 18, 21, 24)
    private const val HAN_WORDS_PER_TYPO = 12
    private const val MAX_HAN_FUNCTION_CHARACTERS = 2
    private const val MIN_LIST_WORD_LENGTH = 3
    private const val MAX_SENTENCE_MARKERS = 1
    private const val MAX_NUMBERING_DIGITS = 2
    private val INNER_NUMBER = Regex("""(?<!\d)\d{1,2}(?!\d)""")

    /** Word to the bit set of lists that contain it. Built on first use: ordinary input stays cheap. */
    private val exact: Map<String, Int> by lazy {
        val map = HashMap<String, Int>(INDEX_CAPACITY)
        BIP39_LISTS.forEachIndexed { language, list ->
            list.split(' ').forEach { word -> map[word] = (map[word] ?: 0) or (1 shl language) }
        }
        map
    }

    private val prefixes: Map<String, Int> by lazy {
        val map = HashMap<String, Int>(INDEX_CAPACITY)
        ALPHABETIC.forEach { language ->
            BIP39_LISTS[language].split(' ').forEach { word ->
                if (word.length >= PREFIX_LENGTH) {
                    val prefix = word.take(PREFIX_LENGTH)
                    map[prefix] = (map[prefix] ?: 0) or (1 shl language)
                }
            }
        }
        map
    }

    private const val INDEX_CAPACITY = 24_000

    /** A named account is an address, although `near` and many names are words on a list. */
    private val NEAR_ACCOUNT = Regex("""(?<![\p{L}\d._-])[\p{L}\d_-]{1,64}\.near(?![\p{L}\d._-])""")
    private const val ADDRESS_PLACEHOLDER = " 0x0 "

    private val SIMPLE_SEPARATORS = Regex("""[\s,;"'\[\]{}()]+""")
    private val NUMBERING = Regex("""^#?\d{1,2}[.):-]$""")
    private val NUMBERED_WORD = Regex("""^#?\d{1,2}[.):-](.+)$""")

    /**
     * Function words of length three or more, in the app's languages: two of them, or two words of
     * one or two letters, mark a sentence rather than a word list.
     */
    private val FUNCTION_WORDS = setOf(
        "the",
        "and",
        "for",
        "with",
        "from",
        "this",
        "that",
        "not",
        "are",
        "was",
        "has",
        "have",
        "you",
        "your",
        "our",
        "but",
        "pro",
        "jen",
        "jsem",
        "jsou",
        "ale",
        "kde",
        "kdo",
        "nebo",
        "jako",
        "tak",
        "aby",
        "pod",
        "nad",
        "jak",
        "ten",
        "tento",
        "und",
        "der",
        "die",
        "das",
        "ein",
        "eine",
        "mit",
        "für",
        "von",
        "ist",
        "nicht",
        "den",
        "dem",
        "des",
        "les",
        "une",
        "est",
        "pour",
        "dans",
        "que",
        "qui",
        "avec",
        "sur",
        "pas",
        "aux",
        "los",
        "las",
        "una",
        "para",
        "con",
        "por",
        "del",
        "más",
        "uma",
        "com",
        "não",
        "dos",
        "das",
        "mas",
        "это",
        "что",
        "как",
        "для",
        "или",
        "при",
        "над",
        "под",
        "мой",
        "моя",
        "yang",
        "dan",
        "untuk",
        "dari",
        "ini",
        "itu",
        "dengan",
    )

    /** Frequent Chinese grammar characters: natural text has them in almost every clause, a random phrase rarely. */
    private const val HAN_FUNCTION_CHARACTERS = "的了是在我有和这不人他们中为上个以要时来用到就说你也能对会可那都而与及或其之着把被从但很将"

    private class Word(val text: String) {
        val folded = SecretText.foldWord(text)
    }

    /** `null` between words stands for an address-like token, which ends a phrase. */
    private fun tokens(text: String): List<Word?> {
        val result = ArrayList<Word?>()
        var index = 0
        while (index < text.length) {
            if (!text[index].isLetterOrDigit() && !SecretText.isWordChar(text[index])) {
                index++
                continue
            }
            val start = index
            while (index < text.length && (text[index].isLetterOrDigit() || SecretText.isWordChar(text[index]))) index++
            addRun(text.substring(start, index), result)
        }
        return result
    }

    private fun addRun(run: String, into: MutableList<Word?>) {
        if (run.all { it.isDigit() }) return // Numbering of the list; says nothing either way.
        val glued = gluedWords(run)
        if (glued != null) {
            glued.forEach { into += Word(it) }
            return
        }
        val letters = run.trimStart { it.isDigit() }.trimEnd { it.isDigit() }
        val numbering = run.length - letters.length
        if (numbering > MAX_NUMBERING_DIGITS || !letters.all(SecretText::isWordChar)) {
            into += null
            return
        }
        // Han characters run into kana in Japanese; split so that each part is judged on its own.
        var start = 0
        for (i in 1..letters.length) {
            if (i == letters.length || SecretText.isHan(letters[i]) != SecretText.isHan(letters[i - 1])) {
                into += Word(letters.substring(start, i))
                start = i
            }
        }
    }

    /**
     * Words joined by their numbers ("1abandon2ability"). Every part must be on a list, because
     * addresses mix letters and digits too.
     */
    private fun gluedWords(run: String): List<String>? {
        val parts = run.split(INNER_NUMBER).filter { it.isNotEmpty() }
        if (parts.size < 2 || parts.any { it.length < MIN_LIST_WORD_LENGTH }) return null
        return parts.takeIf { list -> list.all { SecretText.foldWord(it) in exact } }
    }

    fun detect(text: String): Boolean {
        val all = tokens(text.replace(NEAR_ACCOUNT, ADDRESS_PLACEHOLDER))
        if (looksLikeUnknownWordList(text)) return true
        if (all.any { it != null && it.text.length >= MIN_RUN_LETTERS && !SecretText.isHan(it.text.first()) && runTogether(it.folded) }) return true
        all.singleOrNull()?.let { only -> if (SecretText.isHan(only.text.first()) && hanRun(only.text)) return true }
        var segment = ArrayList<Word>()
        for (token in all) {
            if (token == null) {
                if (hasPhrase(segment)) return true
                segment = ArrayList()
            } else {
                segment += token
            }
        }
        return hasPhrase(segment)
    }

    private fun hasPhrase(segment: List<Word>): Boolean {
        if (segment.size < MIN_WORDS) return false
        val window = minOf(segment.size, WINDOW)
        val minExact = (window * MIN_EXACT_RATIO).toInt().let { if (it < window * MIN_EXACT_RATIO) it + 1 else it }
        val hits = segment.map { exact[it.folded] ?: 0 }
        val prefixHits = segment.map { word ->
            if (word.folded.length >= PREFIX_LENGTH) prefixes[word.folded.take(PREFIX_LENGTH)] ?: 0 else 0
        }
        for (language in 0 until LANGUAGES) {
            val bit = 1 shl language
            var exactCount = 0
            var looseCount = 0
            for (i in segment.indices) {
                if (hits[i] and bit != 0) exactCount++
                if ((hits[i] or prefixHits[i]) and bit != 0) looseCount++
                if (i >= window) {
                    if (hits[i - window] and bit != 0) exactCount--
                    if ((hits[i - window] or prefixHits[i - window]) and bit != 0) looseCount--
                }
                if (i >= window - 1 && (exactCount >= minExact || looseCount >= window - 1)) return true
            }
        }
        return false
    }

    /** Words of one list with nothing in between, as produced by removing the spaces of a phrase. */
    private fun runTogether(letters: String): Boolean {
        val index = exact
        for (language in 0 until LANGUAGES) {
            val bit = 1 shl language
            val words = IntArray(letters.length + 1)
            for (end in 1..letters.length) {
                for (length in 1..minOf(MAX_WORD_LENGTH, end)) {
                    val start = end - length
                    val hit = index[letters.substring(start, end)] ?: continue
                    if (hit and bit != 0 && words[start] + 1 > words[end]) words[end] = words[start] + 1
                }
                if (words[end] >= MIN_WORDS) return true
            }
        }
        return false
    }

    /**
     * Chinese has no spaces, so natural text is also a long run of common characters. A phrase is
     * set apart by being the whole input, its exact length and having almost no grammar characters.
     */
    private fun hanRun(run: String): Boolean {
        if (run.length !in HAN_SEED_LENGTHS) return false
        val typos = run.length / HAN_WORDS_PER_TYPO
        val grammar = run.filter { it in HAN_FUNCTION_CHARACTERS }.toSet().size
        if (grammar > MAX_HAN_FUNCTION_CHARACTERS * typos) return false
        return listOf(CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL).any { language ->
            val bit = 1 shl language
            run.count { (exact[it.toString()] ?: 0) and bit != 0 } >= run.length - typos
        }
    }

    /**
     * Fail closed for word lists the app does not know: the whole input is a seed length of plain
     * letter words in one style, without the small words a sentence would have.
     */
    private fun looksLikeUnknownWordList(text: String): Boolean {
        val tokens = text.split(SIMPLE_SEPARATORS).filter { it.isNotEmpty() && !NUMBERING.matches(it) && it.toIntOrNull() == null }
        if (tokens.size !in SEED_LENGTHS) return false
        val words = tokens.map { NUMBERED_WORD.matchEntire(it)?.groupValues?.get(1) ?: it }
        if (!words.all { word -> word.all(SecretText::isWordChar) && word.length <= MAX_WORD_LENGTH }) return false
        if (words.first().first().isUpperCase() && words.drop(1).all { w -> w.none { it.isUpperCase() } }) return false // Sentence case.
        val folded = words.map { it.lowercase() }
        val markers = folded.count { it.length < MIN_LIST_WORD_LENGTH || it in FUNCTION_WORDS }
        return markers <= MAX_SENTENCE_MARKERS
    }
}
