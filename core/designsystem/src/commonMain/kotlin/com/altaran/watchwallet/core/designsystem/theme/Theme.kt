package com.altaran.watchwallet.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle

/**
 * Returns the platform's dynamic (wallpaper-based) color scheme, or `null` when the platform
 * does not support it (Android < 12, iOS).
 */
@Composable
internal expect fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme?

/** `true` when [platformDynamicColorScheme] can return a scheme on this device. */
expect val isDynamicColorSupported: Boolean

/**
 * Makes system bar icons readable for the app's theme, which may differ from the system theme
 * (for example a light app theme while the device is in dark mode).
 */
@Composable
internal expect fun SystemBarsAppearance(darkTheme: Boolean)

@Composable
fun WatchWalletTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = (if (dynamicColor) platformDynamicColorScheme(darkTheme) else null)
        ?: if (darkTheme) BrandDarkColorScheme else BrandLightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    SystemBarsAppearance(darkTheme)
    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content,
        )
    }
}

/** Convenience accessors for design-system values that live outside [MaterialTheme]. */
object WatchWalletTheme {
    val extendedColors: ExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalExtendedColors.current
}

/** Tabular (fixed-width) figures so that amounts line up and do not jitter while updating. */
fun TextStyle.tabularNumbers(): TextStyle = copy(fontFeatureSettings = "tnum")
