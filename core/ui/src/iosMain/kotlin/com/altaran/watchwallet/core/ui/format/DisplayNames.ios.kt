package com.altaran.watchwallet.core.ui.format

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCurrencyCode
import platform.Foundation.NSLocaleIdentifier
import platform.Foundation.currentLocale

actual object DisplayNames {
    actual fun currencyName(isoCode: String): String? =
        NSLocale.currentLocale.displayNameForKey(NSLocaleCurrencyCode, isoCode)
            ?.replaceFirstChar { it.titlecase() }

    actual fun languageNativeName(languageTag: String): String {
        val locale = NSLocale(localeIdentifier = languageTag)
        return (locale.displayNameForKey(NSLocaleIdentifier, languageTag) ?: languageTag)
            .replaceFirstChar { it.titlecase() }
    }
}
