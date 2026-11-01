package com.altaran.watchwallet.core.common

import java.util.Locale

actual object DeviceLocale {
    actual fun languageTag(): String = Locale.getDefault().toLanguageTag()
}
