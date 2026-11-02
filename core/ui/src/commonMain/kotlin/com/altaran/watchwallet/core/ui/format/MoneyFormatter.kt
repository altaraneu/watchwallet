package com.altaran.watchwallet.core.ui.format

import com.altaran.watchwallet.core.model.QuoteCurrency
import kotlin.time.Instant

/**
 * Locale-aware formatting of values in the user's quote currency.
 *
 * The locale (decimal and grouping separators, symbol position) follows the app language,
 * independently of which currency is selected: a Czech user viewing USD sees `1 234,56 US$`.
 */
expect object MoneyFormatter {
    fun format(value: Double, currency: QuoteCurrency): String

    /** Signed change, for example `+1 234,56 Kč` or `−12,30 Kč`. */
    fun formatChange(value: Double, currency: QuoteCurrency): String

    /** Signed percentage, for example `+4,2 %`. */
    fun formatPercentChange(fraction: Double): String

    /** Unit price; small prices get more fraction digits (`0,000012 Kč`). */
    fun formatPrice(value: Double, currency: QuoteCurrency): String

    /**
     * Crypto amount with its symbol, for example `0,4213 BTC`. Uses up to [maxDecimals] fraction
     * digits and fewer for large amounts, never scientific notation.
     */
    fun formatCrypto(value: Double, symbol: String, maxDecimals: Int = 8): String

    /** Plain percentage without sign, for example `42 %`. */
    fun formatPercent(fraction: Double): String

    /**
     * Keeps a formatted amount together in right-to-left languages, where the sign and the
     * currency would otherwise jump to the wrong side. For amounts built from several parts.
     */
    fun isolated(text: String): String
}

/** Locale-aware date formatting. */
expect object DateFormatter {
    /** Medium date and short time, for example `1. 3. 2024 14:05`. */
    fun dateTime(instant: Instant): String

    /** Medium date only. */
    fun date(instant: Instant): String
}

internal object FormatRules {
    /** Fraction digits for a crypto amount: fewer for large amounts, more for dust. */
    fun cryptoDecimals(value: Double, maxDecimals: Int): Int {
        val magnitude = kotlin.math.abs(value)
        return when {
            magnitude >= 10_000 -> 2
            magnitude >= 100 -> 4
            magnitude >= 1 -> minOf(6, maxDecimals)
            else -> maxDecimals
        }
    }

    fun priceDecimals(value: Double, base: Int): Int {
        val magnitude = kotlin.math.abs(value)
        return when {
            magnitude == 0.0 || magnitude >= 1 -> base
            magnitude >= 0.01 -> maxOf(base, 4)
            magnitude >= 0.0001 -> 6
            else -> 8
        }
    }
}
