package com.altaran.watchwallet.core.common

/** Read-only facts about the current locale, implemented per platform. */
expect object DeviceLocale {
    /** BCP 47 tag of the current default locale (for example `cs-CZ`). Follows the app language. */
    fun languageTag(): String
}
