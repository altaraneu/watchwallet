package com.altaran.watchwallet.core.ui.format

import java.util.Currency
import java.util.Locale

actual object DisplayNames {
    actual fun currencyName(isoCode: String): String? =
        runCatching { Currency.getInstance(isoCode).getDisplayName(Locale.getDefault()) }
            .getOrNull()
            ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }

    actual fun languageNativeName(languageTag: String): String {
        val locale = Locale.forLanguageTag(languageTag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }
}
