package com.altaran.watchwallet.core.data.repository

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoinGeckoCreditTest {
    private fun holding(priceId: String?, value: Double?) =
        Holding(priceId ?: "asset", "SYM", "Name", priceId, 1.0, value, null, value, emptyList(), setOf(1L))

    @Test
    fun creditsOnlyAnAmountThatUsedItsData() {
        val ids = setOf("long-tail")
        assertTrue(listOf(holding("bitcoin", 5.0), holding("long-tail", 1.0)).usesCoinGecko(ids))
        assertFalse(listOf(holding("bitcoin", 5.0)).usesCoinGecko(ids))
        assertFalse(listOf(holding(null, null)).usesCoinGecko(ids))
        // Without a value nothing from CoinGecko is on the screen.
        assertFalse(listOf(holding("long-tail", null)).usesCoinGecko(ids))
        assertFalse(emptyList<Holding>().usesCoinGecko(ids))
    }
}
