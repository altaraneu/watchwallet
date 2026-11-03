package com.altaran.watchwallet.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomServerUrlTest {
    @Test
    fun anyHttpsServerIsAllowed() {
        listOf("https://mempool.example.org/api", "https://example.org", "https://1.2.3.4:3006/api", "https://[2001:db8::1]/api").forEach {
            assertEquals(it, CustomServerUrl.validate(it), it)
        }
        assertEquals("https://example.org/api", CustomServerUrl.validate("  https://example.org/api/ "))
    }

    @Test
    fun plainHttpOnlyForLocalServers() {
        listOf(
            "http://abcdefghijklmnop.onion/api",
            "http://localhost:3006/api",
            "http://127.0.0.1:3006",
            "http://10.0.0.5/api",
            "http://172.16.0.1/api",
            "http://172.31.255.1/api",
            "http://192.168.1.10:3006/api",
            "http://169.254.1.1",
            "http://umbrel.local/api",
            "http://[::1]:3006",
            "http://[fd00::1]/api",
            "http://[fe80::1]/api",
        ).forEach { assertEquals(it, CustomServerUrl.validate(it), it) }
    }

    @Test
    fun onlyAStoredValueThatFailsTheRulesCountsAsIgnored() {
        assertEquals(false, CustomServerUrl.isIgnored(null))
        assertEquals(false, CustomServerUrl.isIgnored("https://mempool.example/api"))
        assertEquals(false, CustomServerUrl.isIgnored("http://192.168.1.10:3006/api"))
        assertEquals(true, CustomServerUrl.isIgnored("http://mempool.space/api"))
        assertEquals(true, CustomServerUrl.isIgnored("mempool.space"))
    }

    @Test
    fun everythingElseIsRejected() {
        listOf(
            "http://mempool.space/api",
            "http://8.8.8.8",
            "http://172.32.0.1",
            "http://192.169.1.1",
            "http://11.0.0.1",
            "http://256.1.1.1",
            "http://10.0.0.1.evil.example",
            "http://localhost.evil.example",
            "http://onion",
            "http://.onion",
            "http://[2001:db8::1]",
            "http://192.168.1.1@evil.example",
            "https://user:password@example.org",
            "http://example.org:99999",
            "ftp://example.org",
            "example.org",
            "",
            "javascript:alert(1)",
            "https://exa mple.org",
        ).forEach { assertNull(CustomServerUrl.validate(it), it) }
    }
}
