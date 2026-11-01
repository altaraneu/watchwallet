package com.altaran.watchwallet.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuoteCurrencyTest {
    @Test
    fun deviceCurrencyIsUsedWhenSupported() {
        assertEquals(QuoteCurrency.CZK, QuoteCurrency.forDeviceCurrency("CZK"))
        assertEquals(QuoteCurrency.EUR, QuoteCurrency.forDeviceCurrency("eur"))
    }

    @Test
    fun unsupportedOrMissingDeviceCurrencyFallsBackToDefault() {
        assertEquals(QuoteCurrency.DEFAULT, QuoteCurrency.forDeviceCurrency("XAF"))
        assertEquals(QuoteCurrency.DEFAULT, QuoteCurrency.forDeviceCurrency(null))
    }

    @Test
    fun cryptoQuoteCurrencyIsNeverPickedAsDeviceDefault() {
        assertEquals(QuoteCurrency.DEFAULT, QuoteCurrency.forDeviceCurrency("BTC"))
    }

    @Test
    fun fromCodeIsCaseInsensitive() {
        assertEquals(QuoteCurrency.BTC, QuoteCurrency.fromCode("btc"))
        assertNull(QuoteCurrency.fromCode("???"))
    }
}
