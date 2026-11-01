package com.altaran.watchwallet.core.ui.format

import com.altaran.watchwallet.core.model.QuoteCurrency

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
}
