package com.altaran.watchwallet.core.security

import java.io.File
import java.text.Normalizer
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecretTextJvmTest {
    /** The hand-written fold must agree with real Unicode normalisation, which iOS shares through it. */
    @Test
    fun foldingIsIndependentOfTheNormalisationForm() {
        BIP39_LISTS.flatMap { it.split(' ') }.forEach { word ->
            listOf(Normalizer.Form.NFC, Normalizer.Form.NFD, Normalizer.Form.NFKC, Normalizer.Form.NFKD).forEach { form ->
                assertEquals(word, SecretText.foldWord(Normalizer.normalize(word, form)), "$word in $form")
            }
        }
    }

    /** Every translation of the app is ordinary text in 13 languages: none of it may look like a seed. */
    @Test
    fun noUserFacingStringOfTheAppLooksLikeASecret() {
        var root = File("").absoluteFile
        while (!File(root, "settings.gradle.kts").exists()) root = root.parentFile
        val files = root.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".git", ".kotlin") }
            .filter { it.name == "strings.xml" }
            .toList()
        assertTrue(files.size > 100, "expected the translation files, found ${files.size}")
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        var checked = 0
        val rejected = files.flatMap { file ->
            val items = builder.parse(file).documentElement.childNodes
            (0 until items.length).map { items.item(it).textContent.trim() }.filter { it.isNotEmpty() }.onEach { checked++ }
                .filter(SecretGuard::isSecret)
                .map { "${file.parentFile.name}: $it" }
        }
        println("checked $checked strings of ${files.size} files")
        assertEquals(emptyList(), rejected)
    }
}
