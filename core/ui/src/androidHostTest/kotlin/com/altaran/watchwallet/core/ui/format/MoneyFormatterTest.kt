package com.altaran.watchwallet.core.ui.format

import com.altaran.watchwallet.core.model.QuoteCurrency
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MoneyFormatterTest {
    private val czech = Locale.forLanguageTag("cs-CZ")
    private val english = Locale.US

    /** Replaces the no-break spaces used by CLDR with `_` so expectations are readable. */
    private fun String.visibleSpaces() = replace(Char(0x00A0), '_').replace(Char(0x202F), '_')

    @Test
    fun czechUsesCommaDecimalAndSpaceGrouping() {
        assertEquals("1_234,56_Kč", MoneyFormatter.format(1234.56, QuoteCurrency.CZK, czech).visibleSpaces())
    }

    @Test
    fun englishUsesDotDecimalAndCommaGrouping() {
        assertEquals("$1,234.56", MoneyFormatter.format(1234.56, QuoteCurrency.USD, english))
    }

    @Test
    fun zeroDecimalCurrencyHasNoFractionDigits() {
        assertEquals("¥1,235", MoneyFormatter.format(1234.56, QuoteCurrency.JPY, english))
    }

    @Test
    fun cryptoQuoteUsesEightDecimalsForBtcAndLocaleSeparators() {
        assertEquals("0,01234568_BTC", MoneyFormatter.format(0.012345678, QuoteCurrency.BTC, czech).visibleSpaces())
        assertEquals("1,234.000000_ETH", MoneyFormatter.format(1234.0, QuoteCurrency.ETH, english).visibleSpaces())
    }

    @Test
    fun changeCarriesExplicitSign() {
        assertEquals("+$12.50", MoneyFormatter.formatChange(12.5, QuoteCurrency.USD, english))
        assertEquals("−$12.50", MoneyFormatter.formatChange(-12.5, QuoteCurrency.USD, english))
        assertEquals("$0.00", MoneyFormatter.formatChange(0.0, QuoteCurrency.USD, english))
    }

    @Test
    fun percentChangeIsLocaleAware() {
        assertEquals("+4.2%", MoneyFormatter.formatPercentChange(0.042, english))
        assertEquals("−4,25_%", MoneyFormatter.formatPercentChange(-0.0425, czech).visibleSpaces())
    }
}
