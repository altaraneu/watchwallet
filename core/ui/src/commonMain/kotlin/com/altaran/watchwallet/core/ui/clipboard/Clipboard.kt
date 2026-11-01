package com.altaran.watchwallet.core.ui.clipboard

import androidx.compose.ui.platform.ClipEntry

/**
 * Creates a plain-text clipboard entry.
 *
 * @param sensitive marks the clip as sensitive (Android 13+), so the system clipboard preview
 * hides it. Use for addresses the user tracks; public donation addresses do not need it.
 */
expect fun plainTextClipEntry(text: String, sensitive: Boolean): ClipEntry
