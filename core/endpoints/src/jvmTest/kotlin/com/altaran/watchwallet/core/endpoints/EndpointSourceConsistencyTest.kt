package com.altaran.watchwallet.core.endpoints

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SECONDS_PER_DAY = 86_400L

/** The published list starts from the bundled one; drift would go unnoticed otherwise. */
class EndpointSourceConsistencyTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val source = Json.parseToJsonElement(File(root, "config/endpoints.source.json").readText()).jsonObject

    @Test
    fun sourceFileEqualsBundledDefaults() {
        val entries = source.getValue("endpoints").jsonArray.map { element ->
            val entry = element.jsonObject
            EndpointEntry(
                entry.getValue("chain").jsonPrimitive.content,
                entry.getValue("protocol").jsonPrimitive.content,
                entry.getValue("urls").jsonArray.map { it.jsonPrimitive.content },
            )
        }
        assertEquals(BundledEndpoints.ENTRIES.toSet(), entries.toSet())
        assertEquals(BundledEndpoints.ENTRIES.size, entries.size)
    }

    @Test
    fun sourceHasAVersion() {
        assertTrue(source.getValue("version").jsonPrimitive.int >= 1)
    }

    @Test
    fun signingToolPinsTheSameKeyAsTheApp() {
        val tool = File(root, "scripts/sign_endpoints.py").readText()
        assertTrue("PINNED_PUBLIC_KEY = \"${EndpointFileVerifier.PINNED_PUBLIC_KEY}\"" in tool)
        assertTrue("MAX_ENVELOPE_BYTES = 256 * 1024" in tool)
        assertTrue("MAX_VALIDITY_DAYS = ${EndpointFile.MAX_VALIDITY_SECONDS / SECONDS_PER_DAY}" in tool)
        EndpointConfigUpdater.DEFAULT_URLS.forEach { url -> assertTrue("\"$url\"" in tool, url) }
    }

    @Test
    fun bundledVersionMatchesTheSource() {
        assertEquals(BundledEndpoints.VERSION, source.getValue("version").jsonPrimitive.long)
    }

    @Test
    fun theSourceExpiresWithinTheLimit() {
        assertTrue(source.getValue("expires_in_days").jsonPrimitive.int * SECONDS_PER_DAY <= EndpointFile.MAX_VALIDITY_SECONDS)
    }

    /** Every default must pass the checks a remote file has to pass, and every host be disclosed. */
    @Test
    fun bundledUrlsPassTheRemoteRulesAndAreInThePrivacyPolicy() {
        val privacy = File(root, "PRIVACY.md").readText()
        val file = (verifier.open(signed(entries = BundledEndpoints.ENTRIES)) as EndpointFileResult.Valid).file
        assertEquals(BundledEndpoints.ENTRIES, file.entries)
        (BundledEndpoints.ENTRIES.flatMap { it.urls } + EndpointConfigUpdater.DEFAULT_URLS).forEach { url ->
            val host = url.removePrefix("https://").substringBefore('/').substringBefore(':')
            assertTrue(isDisclosed(host, privacy), "$host is missing in PRIVACY.md")
        }
    }

    /** PRIVACY.md abbreviates families of hosts as `*.publicnode.com` and `eth.`, `base.blockscout.com`. */
    private fun isDisclosed(host: String, privacy: String): Boolean =
        host in privacy ||
            "*.${host.substringAfter('.')}" in privacy ||
            ("${host.substringBefore('.')}.`" in privacy && host.substringAfter('.') in privacy)
}
