package com.altaran.watchwallet.core.model

/**
 * Currency that portfolio values are displayed in.
 *
 * Fiat entries use their ISO 4217 code. BTC and ETH are supported as quote currencies too.
 * The order of entries is the order shown in the currency picker.
 */
enum class QuoteCurrency(
    val code: String,
    val kind: Kind,
    /** Fraction digits used when displaying a value in this currency. */
    val displayDecimals: Int,
) {
    USD("USD", Kind.FIAT, 2),
    EUR("EUR", Kind.FIAT, 2),
    CZK("CZK", Kind.FIAT, 2),
    GBP("GBP", Kind.FIAT, 2),
    CHF("CHF", Kind.FIAT, 2),
    PLN("PLN", Kind.FIAT, 2),
    HUF("HUF", Kind.FIAT, 0),
    SEK("SEK", Kind.FIAT, 2),
    NOK("NOK", Kind.FIAT, 2),
    DKK("DKK", Kind.FIAT, 2),
    JPY("JPY", Kind.FIAT, 0),
    CAD("CAD", Kind.FIAT, 2),
    AUD("AUD", Kind.FIAT, 2),
    BTC("BTC", Kind.CRYPTO, 8),
    ETH("ETH", Kind.CRYPTO, 6),
    ;

    enum class Kind { FIAT, CRYPTO }

    companion object {
        val DEFAULT: QuoteCurrency = USD

        fun fromCode(code: String?): QuoteCurrency? =
            code?.let { value -> entries.firstOrNull { it.code.equals(value, ignoreCase = true) } }

        /** The device locale's currency if we support it, otherwise [DEFAULT]. */
        fun forDeviceCurrency(deviceCurrencyCode: String?): QuoteCurrency =
            fromCode(deviceCurrencyCode)?.takeIf { it.kind == Kind.FIAT } ?: DEFAULT
    }
}
