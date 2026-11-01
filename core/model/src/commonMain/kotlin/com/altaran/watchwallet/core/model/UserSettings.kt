package com.altaran.watchwallet.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * User preferences persisted in DataStore.
 *
 * The app language is intentionally not part of this model: it is owned by the platform
 * (per-app language on Android), see `AppLanguageController`.
 */
data class UserSettings(
    val quoteCurrency: QuoteCurrency,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val discreetMode: Boolean = false,
)
