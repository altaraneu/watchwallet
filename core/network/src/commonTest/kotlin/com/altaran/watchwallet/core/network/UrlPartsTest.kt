package com.altaran.watchwallet.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UrlPartsTest {
    @Test
    fun validValuesStayAsTheyAre() {
        listOf(
            "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
            "bitcoincash:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
            "kaspa:qqkqkzjvr7zwxxmjxjkmxxdwju9kjs6e9u82uh59z07vgaks6gg62v8707g73",
            "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
            "0.0.456858",
            "tz1burnburnburnburnburnburnburjAYjjX",
            "EQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAM9c",
            "near-account_1.near",
        ).forEach { assertEquals(it, pathSegment(it)) }
    }

    @Test
    fun whatWouldChangeThePathIsEncoded() {
        assertEquals("..%2F..%2Fadmin", pathSegment("../../admin"))
        assertEquals("a%3Fx=1", pathSegment("a?x=1"))
        assertEquals("a%23b", pathSegment("a#b"))
        assertEquals("a%2Fb", pathSegment("a/b"))
        assertEquals("a%252Fb", pathSegment("a%2Fb"))
        assertEquals("a%20b", pathSegment("a b"))
    }

    @Test
    fun segmentsEncodingCannotMakeSafeAreRefused() {
        listOf("", ".", "..").forEach { value -> assertFailsWith<IllegalArgumentException> { pathSegment(value) } }
    }
}
