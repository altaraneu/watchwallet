package com.altaran.watchwallet.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MnemonicDetectionTest {
    private val names = listOf(
        "english",
        "japanese",
        "korean",
        "spanish",
        "chinese simplified",
        "chinese traditional",
        "french",
        "italian",
        "czech",
        "portuguese",
    )

    /** Twelve words spread over the list: public words in an arbitrary order, never a real wallet. */
    private fun phrase(language: Int, words: Int = WORDS): List<String> {
        val list = BIP39_LISTS[language].split(' ')
        return List(words) { list[(it * STEP + OFFSET) % list.size] }
    }

    private fun assertMnemonic(input: String, name: String) =
        assertEquals(SecretCheck.Secret(SecretKind.MNEMONIC), SecretGuard.check(input), name)

    private fun assertClean(input: String) = assertEquals(SecretCheck.Clean, SecretGuard.check(input), input)

    @Test
    fun everyListHasAllItsWordsInFoldedForm() {
        assertEquals(LANGUAGES, BIP39_LISTS.size)
        BIP39_LISTS.forEachIndexed { i, list ->
            val words = list.split(' ')
            assertEquals(LIST_SIZE, words.size, names[i])
            assertEquals(LIST_SIZE, words.toSet().size, names[i])
            words.forEach { assertEquals(it, SecretText.foldWord(it), names[i]) }
        }
    }

    @Test
    fun allOfficialLanguagesInAllFormats() {
        for (language in 0 until LANGUAGES) {
            val w = phrase(language)
            val name = names[language]
            mapOf(
                "spaces" to w.joinToString(" "),
                "lines" to w.joinToString("\n"),
                "commas" to w.joinToString(", "),
                "ideographic spaces" to w.joinToString(Char(0x3000).toString()),
                "no-break spaces" to w.joinToString(Char(0x00A0).toString()),
                "numbered" to w.mapIndexed { i, x -> "${i + 1}. $x" }.joinToString(" "),
                "numbered lines" to w.mapIndexed { i, x -> "${i + 1}) $x" }.joinToString("\n"),
                "hyphens" to w.joinToString("-"),
                "json" to w.joinToString(",", "[", "]") { "\"$it\"" },
                "24 words" to phrase(language, 24).joinToString(" "),
                "run together" to w.joinToString(""),
            ).forEach { (format, input) -> assertMnemonic(input, "$name, $format") }
        }
    }

    @Test
    fun typosAreTolerated() {
        for (language in listOf(0, 3, 6, 7, 8, 9)) {
            val w = phrase(language).toMutableList()
            w[1] = w[1].dropLast(1) + "q"
            w[5] = w[5].take(2) + "qq" + w[5].drop(4)
            w[9] = w[9].dropLast(1) + "zz"
            assertMnemonic(w.joinToString(" "), "${names[language]} with typos")
        }
    }

    @Test
    fun longNotesAreCaughtWithoutCountingTheWholeNote() {
        val w = phrase(0).joinToString(" ")
        assertMnemonic("Please keep this safe, it took me a long time to find a good place. $w. Thanks for everything!", "note")
        assertMnemonic(phrase(8).joinToString(" ") + " a tohle je zbytek poznámky, který nic neznamená a je dlouhý", "Czech note")
    }

    @Test
    fun unknownWordListsFailClosed() {
        // Word lists the app does not carry, by shape: exact seed length, plain words, no sentence.
        val made = List(25) { i -> "wor" + "dlist"[i % 5] + "abcdefghij"[i % 10] }
        assertMnemonic(made.joinToString(" "), "25 unknown words")
        assertMnemonic(made.take(12).joinToString("\n"), "12 unknown words")
        assertMnemonic(made.take(12).mapIndexed { i, x -> "${i + 1}. $x" }.joinToString(" "), "12 unknown numbered")
        assertMnemonic(List(12) { "слово" }.joinToString(" "), "Cyrillic words")
        // Not the seed length, or a sentence: passes.
        assertClean(made.take(11).joinToString(" "))
        assertClean(made.take(14).joinToString(" "))
    }

    /** Ordinary text must pass. Every sentence here has twelve or more words. */
    @Test
    fun ordinaryNamesAndNotesPass() {
        listOf(
            "My cold storage",
            "Savings for the kids",
            "Peněženka pro Janu",
            "Tohle je moje dlouhodobá peněženka pro úspory a nechci z ní nic posílat.",
            "Tohle je moje dlouhodobá peněženka pro úspory a nechci z ní nic posílat",
            "tohle je moje dlouhodobá peněženka pro úspory a nechci z ní nic posílat",
            "Koupil jsem to v roce 2019 od kamaráda, když jsme byli spolu na horách a měli čas.",
            "Na tuto adresu mi chodí výplata z práce a každý měsíc část převádím do úspor.",
            "Peníze pro děti, které dostanou až po dovršení osmnácti let, nesahat na ně dřív.",
            "This is the wallet I use for long term savings and I never send anything from it.",
            "Bought on an exchange in the spring and moved here after the price went up a little.",
            "the wallet of my family for the holiday house and the car we want to buy next year",
            "Das ist meine Geldbörse für langfristige Ersparnisse, aus der ich nichts versende, solange es geht.",
            "Ceci est mon portefeuille pour les économies à long terme et je ne veux rien envoyer.",
            "Esta es mi cartera para ahorros a largo plazo y no quiero enviar nada desde aquí.",
            "Esta é a minha carteira para poupança de longo prazo e não quero enviar nada daqui.",
            "Ini adalah dompet saya untuk tabungan jangka panjang dan saya tidak ingin mengirim apa pun.",
            "Это мой кошелёк для долгосрочных сбережений, и я не хочу ничего из него отправлять.",
            "これは長期貯蓄用のウォレットで、ここからは何も送金しないつもりです。家族の将来のために保管しています。",
            "这是我的长期储蓄钱包,我不打算从这里发送任何东西,一切都是为了家人的未来。",
            "這是我的長期儲蓄錢包,我不打算從這裡發送任何東西,一切都是為了家人的未來。",
            "比特币钱包长期保存",
            "यह मेरा दीर्घकालिक बचत वॉलेट है और मैं इससे कुछ भी भेजना नहीं चाहता हूँ।",
            "এটি আমার দীর্ঘমেয়াদী সঞ্চয়ের ওয়ালেট এবং আমি এখান থেকে কিছু পাঠাতে চাই না।",
            "هذه محفظتي للادخار طويل الأجل ولا أريد إرسال أي شيء منها إلى أي مكان آخر.",
        ).forEach(::assertClean)
    }

    @Test
    fun listsOfAddressesAndDescriptorsPass() {
        val btc = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val eth = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
        val xpub = "xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj"
        assertClean(List(30) { btc }.joinToString("\n"))
        assertClean(List(12) { "$eth," }.joinToString(" "))
        assertClean(List(24) { xpub }.joinToString(" "))
        assertClean(List(12) { "Cold wallet family, $btc" }.joinToString("\n"))
        assertClean(List(12) { i -> "account$i.near" }.joinToString(" "))
        assertClean(List(12) { "apple.near" }.joinToString(" ").replace("apple", "alice"))
        assertClean(List(40) { i -> "0.0.${1000 + i}" }.joinToString(" "))
        assertClean("wpkh([d34db33f/84'/0'/0']$xpub/0/*)#abcd1234")
        assertClean("label,chain,type,address_or_xpub,group,tags,notes\n" + List(20) { "Savings,bitcoin,address,$btc,Family,,Cold storage" }.joinToString("\n"))
    }

    @Test
    fun searchQueriesAreLimitedToCoinNames() {
        listOf(
            "bitcoin",
            "btc",
            "1inch",
            "0x",
            "usd-coin",
            "wrapped bitcoin",
            "The Graph",
            "shiba inu",
            "uniswap v3",
            "Lido Staked Ether",
            "žeton",
            "比特币",
        ).forEach {
            assertTrue(SearchQueryGuard.isAllowed(it), it)
        }
        listOf(
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
            "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "0x5aAeb6",
            "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU",
            "TLyqzVGLV1srkB7dToTAEqgDSfPtXRJZYH",
            "xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj",
            "0c28fca386c7a227",
            "0.0.1234567",
            "alice.near",
            "this is way more than a few words",
            "one two three four five six",
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "a".repeat(41),
        ).forEach { assertFalse(SearchQueryGuard.isAllowed(it), it) }
    }

    @Test
    fun guardResultCarriesNoInput() {
        val result = assertIs<SecretCheck.Secret>(SecretGuard.check(phrase(0).joinToString(" ")))
        assertEquals("Secret(kind=MNEMONIC)", result.toString())
    }

    private companion object {
        const val LANGUAGES = 10
        const val LIST_SIZE = 2048
        const val WORDS = 12
        const val STEP = 331
        const val OFFSET = 977
    }
}
