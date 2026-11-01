package com.altaran.watchwallet.core.ui.format

/** Localized display names supplied by the platform's locale data (CLDR/ICU). */
expect object DisplayNames {
    /** Name of an ISO 4217 currency in the current app language, for example `česká koruna`. */
    fun currencyName(isoCode: String): String?

    /** Name of a language in that language itself, for example `Čeština` for `cs`. */
    fun languageNativeName(languageTag: String): String
}
