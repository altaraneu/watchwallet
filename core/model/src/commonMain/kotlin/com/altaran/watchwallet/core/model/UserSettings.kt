package com.altaran.watchwallet.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Network privacy and data-source settings. Changes take effect after an app restart. */
data class NetworkSettings(
    /** Route all traffic through a SOCKS5 proxy (for example Orbot on 127.0.0.1:9050). */
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "127.0.0.1",
    val proxyPort: Int = 9050,
    /** Custom Esplora base URL for Bitcoin (own electrs/esplora), or `null` for the default. */
    val esploraUrl: String? = null,
    /** Optional CoinGecko demo API key for higher rate limits. */
    val coinGeckoApiKey: String? = null,
    /** Use the signed server list from the project repository; off means bundled servers only. */
    val autoUpdateEndpoints: Boolean = true,
)

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
    /** Require biometrics or the device credential when opening the app. */
    val appLock: Boolean = false,
    /** Block screenshots and hide the app content in the recent-apps preview. */
    val secureScreen: Boolean = false,
    /** Load coin logos from the internet (Trust Wallet assets); otherwise bundled icons only. */
    val remoteIcons: Boolean = true,
    /** Background refresh interval in hours; 0 = off. */
    val backgroundRefreshHours: Int = 0,
    val backgroundRefreshWifiOnly: Boolean = true,
    val network: NetworkSettings = NetworkSettings(),
)
