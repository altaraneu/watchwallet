package com.altaran.watchwallet.core.ui.format

import com.altaran.watchwallet.core.model.QuoteCurrency
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Keeps the amount and its currency code on one line. */
private val NO_BREAK_SPACE = Char(0x00A0)

actual object MoneyFormatter {
    actual fun format(value: Double, currency: QuoteCurrency): String =
        format(value, currency, Locale.getDefault())

    actual fun formatChange(value: Double, currency: QuoteCurrency): String =
        formatChange(value, currency, Locale.getDefault())

    actual fun formatPercentChange(fraction: Double): String =
        formatPercentChange(fraction, Locale.getDefault())

    internal fun format(value: Double, currency: QuoteCurrency, locale: Locale): String =
        when (currency.kind) {
            QuoteCurrency.Kind.FIAT -> {
                NumberFormat.getCurrencyInstance(locale).apply {
                    this.currency = Currency.getInstance(currency.code)
                    minimumFractionDigits = currency.displayDecimals
                    maximumFractionDigits = currency.displayDecimals
                }.format(value)
            }

            // Crypto quote currencies are not ISO 4217: format the number and append the code.
            QuoteCurrency.Kind.CRYPTO -> {
                val number = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(locale)).apply {
                    minimumFractionDigits = currency.displayDecimals
                    maximumFractionDigits = currency.displayDecimals
                }.format(value)
                "$number$NO_BREAK_SPACE${currency.code}"
            }
        }

    internal fun formatChange(value: Double, currency: QuoteCurrency, locale: Locale): String {
        val magnitude = format(kotlin.math.abs(value), currency, locale)
        return signPrefix(value) + magnitude
    }

    internal fun formatPercentChange(fraction: Double, locale: Locale): String {
        val magnitude = NumberFormat.getPercentInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 2
        }.format(kotlin.math.abs(fraction))
        return signPrefix(fraction) + magnitude
    }

    // U+2212 MINUS SIGN reads better than a hyphen next to digits and is announced by TalkBack.
    private fun signPrefix(value: Double): String = when {
        value > 0 -> "+"
        value < 0 -> "−"
        else -> ""
    }
}
