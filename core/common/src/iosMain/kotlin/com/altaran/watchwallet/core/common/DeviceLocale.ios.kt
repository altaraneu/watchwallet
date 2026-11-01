package com.altaran.watchwallet.core.common

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

actual object DeviceLocale {
    actual fun languageTag(): String = NSLocale.currentLocale.localeIdentifier.replace('_', '-')
}
