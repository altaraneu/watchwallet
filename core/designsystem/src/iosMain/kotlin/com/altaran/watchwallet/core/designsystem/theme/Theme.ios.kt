package com.altaran.watchwallet.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
internal actual fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

actual val isDynamicColorSupported: Boolean = false

// iosApp will set the status bar style from the hosting UIViewController.
@Composable
internal actual fun SystemBarsAppearance(darkTheme: Boolean) = Unit
