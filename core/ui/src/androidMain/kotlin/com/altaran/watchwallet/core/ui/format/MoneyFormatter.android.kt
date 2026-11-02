package com.altaran.watchwallet.core.ui.format

import com.altaran.watchwallet.core.model.QuoteCurrency
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Date
import java.util.Locale
import kotlin.time.Instant

/** Keeps the amount and its currency code on one line. */
private val NO_BREAK_SPACE = Char(0x00A0)

// U+2212 MINUS SIGN reads better than a hyphen next to digits and is announced by TalkBack.
private val MINUS_SIGN = Char(0x2212)

actual object MoneyFormatter {
    actual fun format(value: Double, currency: QuoteCurrency): String =
        isolated(format(value, currency, Locale.getDefault()))

    actual fun formatChange(value: Double, currency: QuoteCurrency): String =
        isolated(formatChange(value, currency, Locale.getDefault()))

    actual fun formatPercentChange(fraction: Double): String =
        isolated(formatPercentChange(fraction, Locale.getDefault()))

    actual fun formatPrice(value: Double, currency: QuoteCurrency): String =
        isolated(format(value, currency, Locale.getDefault(), FormatRules.priceDecimals(value, currency.displayDecimals)))

    actual fun formatPriceChange(value: Double, currency: QuoteCurrency): String =
        isolated(formatPriceChange(value, currency, Locale.getDefault()))

    actual fun formatCrypto(value: Double, symbol: String, maxDecimals: Int): String =
        isolated(formatCrypto(value, symbol, maxDecimals, Locale.getDefault()))

    actual fun formatPercent(fraction: Double): String = isolated(
        NumberFormat.getPercentInstance(Locale.getDefault()).apply {
            maximumFractionDigits = if (fraction < 0.1) 1 else 0
        }.format(fraction),
    )

    actual fun isolated(text: String): String =
        if (android.text.TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == android.view.View.LAYOUT_DIRECTION_RTL) {
            "$LEFT_TO_RIGHT_ISOLATE$text$POP_DIRECTIONAL_ISOLATE"
        } else {
            text
        }

    // Built from code points: ktlint rewrites unicode escapes.
    private val LEFT_TO_RIGHT_ISOLATE = Char(0x2066)
    private val POP_DIRECTIONAL_ISOLATE = Char(0x2069)

    internal fun format(value: Double, currency: QuoteCurrency, locale: Locale, decimals: Int = currency.displayDecimals): String =
        when (currency.kind) {
            QuoteCurrency.Kind.FIAT -> {
                NumberFormat.getCurrencyInstance(locale).apply {
                    this.currency = Currency.getInstance(currency.code)
                    minimumFractionDigits = decimals
                    maximumFractionDigits = decimals
                }.format(value)
            }

            // Crypto quote currencies are not ISO 4217: format the number and append the code.
            QuoteCurrency.Kind.CRYPTO -> {
                val number = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(locale)).apply {
                    minimumFractionDigits = currency.displayDecimals
                    maximumFractionDigits = maxOf(decimals, currency.displayDecimals)
                }.format(value)
                "$number$NO_BREAK_SPACE${currency.code}"
            }
        }

    internal fun formatCrypto(value: Double, symbol: String, maxDecimals: Int, locale: Locale): String {
        val number = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(locale)).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = FormatRules.cryptoDecimals(value, maxDecimals)
        }.format(value)
        return "$number$NO_BREAK_SPACE$symbol"
    }

    internal fun formatChange(value: Double, currency: QuoteCurrency, locale: Locale): String {
        val magnitude = format(kotlin.math.abs(value), currency, locale)
        return signPrefix(value) + magnitude
    }

    internal fun formatPriceChange(value: Double, currency: QuoteCurrency, locale: Locale): String {
        val magnitude = kotlin.math.abs(value)
        return signPrefix(value) + format(magnitude, currency, locale, FormatRules.priceDecimals(magnitude, currency.displayDecimals))
    }

    internal fun formatPercentChange(fraction: Double, locale: Locale): String {
        val magnitude = NumberFormat.getPercentInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 2
        }.format(kotlin.math.abs(fraction))
        return signPrefix(fraction) + magnitude
    }

    private fun signPrefix(value: Double): String = when {
        value > 0 -> "+"
        value < 0 -> MINUS_SIGN.toString()
        else -> ""
    }
}

actual object DateFormatter {
    actual fun dateTime(instant: Instant): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
            .format(Date(instant.toEpochMilliseconds()))

    actual fun date(instant: Instant): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(instant.toEpochMilliseconds()))
}
