package com.altaran.watchwallet.core.data.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * Per-app language selection. Owned by the platform: on Android it maps to
 * `AppCompatDelegate.setApplicationLocales` (system per-app language on Android 13+).
 */
interface AppLanguageController {
    /** BCP 47 tags of all bundled translations, generated from the resource folders at build time. */
    val supportedLanguageTags: List<String>

    /** The explicitly selected language tag, or `null` when following the system language. */
    val selectedLanguageTag: StateFlow<String?>

    /** Selects a language; `null` follows the system language again. */
    fun setLanguage(tag: String?)
}

/** Static information about the running build, provided by the app shell. */
data class AppInfo(
    val versionName: String,
    val versionCode: Long,
    val sourceCodeUrl: String,
    val isDynamicColorAvailable: Boolean,
    /** Debug builds show developer tools (demo data). */
    val isDebug: Boolean = false,
    /** Biometrics or a device credential are set up, so the app lock can be offered. */
    val isAppLockAvailable: Boolean = false,
)

/** Restarts the app so network settings (proxy, endpoints) take effect. */
fun interface AppRestarter {
    fun restart()
}
