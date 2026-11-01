package com.altaran.watchwallet.core.ui.format

import com.altaran.watchwallet.core.model.QuoteCurrency
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterPercentStyle
import platform.Foundation.currentLocale
import kotlin.math.abs

/** Keeps the amount and its currency code on one line. */
private val NO_BREAK_SPACE = Char(0x00A0)

actual object MoneyFormatter {
    actual fun format(value: Double, currency: QuoteCurrency): String {
        val formatter = NSNumberFormatter().apply {
            locale = NSLocale.currentLocale
            minimumFractionDigits = currency.displayDecimals.toULong()
            maximumFractionDigits = currency.displayDecimals.toULong()
        }
        return when (currency.kind) {
            QuoteCurrency.Kind.FIAT -> {
                formatter.numberStyle = NSNumberFormatterCurrencyStyle
                formatter.currencyCode = currency.code
                formatter.stringFromNumber(NSNumber(value)) ?: value.toString()
            }

            QuoteCurrency.Kind.CRYPTO -> {
                formatter.numberStyle = NSNumberFormatterDecimalStyle
                (formatter.stringFromNumber(NSNumber(value)) ?: value.toString()) + NO_BREAK_SPACE + currency.code
            }
        }
    }

    actual fun formatChange(value: Double, currency: QuoteCurrency): String =
        signPrefix(value) + format(abs(value), currency)

    actual fun formatPercentChange(fraction: Double): String {
        val formatter = NSNumberFormatter().apply {
            locale = NSLocale.currentLocale
            numberStyle = NSNumberFormatterPercentStyle
            minimumFractionDigits = 1u
            maximumFractionDigits = 2u
        }
        return signPrefix(fraction) + (formatter.stringFromNumber(NSNumber(abs(fraction))) ?: "")
    }

    private fun signPrefix(value: Double): String = when {
        value > 0 -> "+"
        value < 0 -> "−"
        else -> ""
    }
}
