package com.altaran.watchwallet.core.ui.format

import com.altaran.watchwallet.core.model.QuoteCurrency
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterPercentStyle
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.math.abs
import kotlin.time.Instant

/** Keeps the amount and its currency code on one line. */
private val NO_BREAK_SPACE = Char(0x00A0)
private val MINUS_SIGN = Char(0x2212)

actual object MoneyFormatter {
    actual fun format(value: Double, currency: QuoteCurrency): String = format(value, currency, currency.displayDecimals)

    private fun format(value: Double, currency: QuoteCurrency, decimals: Int): String {
        val formatter = NSNumberFormatter().apply {
            locale = NSLocale.currentLocale
            minimumFractionDigits = decimals.toULong()
            maximumFractionDigits = decimals.toULong()
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

    actual fun formatPrice(value: Double, currency: QuoteCurrency): String =
        format(value, currency, FormatRules.priceDecimals(value, currency.displayDecimals))

    actual fun formatCrypto(value: Double, symbol: String, maxDecimals: Int): String {
        val formatter = NSNumberFormatter().apply {
            locale = NSLocale.currentLocale
            numberStyle = NSNumberFormatterDecimalStyle
            minimumFractionDigits = 0u
            maximumFractionDigits = FormatRules.cryptoDecimals(value, maxDecimals).toULong()
        }
        return (formatter.stringFromNumber(NSNumber(value)) ?: value.toString()) + NO_BREAK_SPACE + symbol
    }

    actual fun formatPercent(fraction: Double): String {
        val formatter = NSNumberFormatter().apply {
            locale = NSLocale.currentLocale
            numberStyle = NSNumberFormatterPercentStyle
            maximumFractionDigits = if (fraction < 0.1) 1u else 0u
        }
        return formatter.stringFromNumber(NSNumber(fraction)) ?: ""
    }

    private fun signPrefix(value: Double): String = when {
        value > 0 -> "+"
        value < 0 -> MINUS_SIGN.toString()
        else -> ""
    }
}

actual object DateFormatter {
    private fun format(instant: Instant, withTime: Boolean): String {
        val formatter = NSDateFormatter().apply {
            locale = NSLocale.currentLocale
            dateStyle = NSDateFormatterMediumStyle
            timeStyle = if (withTime) NSDateFormatterShortStyle else NSDateFormatterNoStyle
        }
        return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(instant.toEpochMilliseconds() / 1000.0))
    }

    actual fun dateTime(instant: Instant): String = format(instant, withTime = true)

    actual fun date(instant: Instant): String = format(instant, withTime = false)
}
