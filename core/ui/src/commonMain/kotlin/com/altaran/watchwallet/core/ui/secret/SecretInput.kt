package com.altaran.watchwallet.core.ui.secret

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboard
import com.altaran.watchwallet.core.security.SecretGuard
import com.altaran.watchwallet.core.ui.clipboard.plainText
import com.altaran.watchwallet.core.ui.generated.resources.Res
import com.altaran.watchwallet.core.ui.generated.resources.secret_field_warning
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Tells the keyboard around [content] not to learn from it (Android); elsewhere it does nothing. */
@Composable
expect fun SensitiveInput(content: @Composable () -> Unit)

/**
 * Clears the clipboard when it holds the rejected text, however it got into the field: other apps
 * and keyboards can read the clipboard. The text is compared and dropped, never kept.
 */
@Composable
fun rememberClipboardScrubber(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { rejected ->
            scope.launch {
                val held = runCatching { clipboard.getClipEntry()?.plainText() }.getOrNull()
                if (held != null && held.trim().isNotEmpty() && rejected.contains(held.trim())) clipboard.setClipEntry(null)
            }
        }
    }
}

/** Secret check for a field whose text is local to the composable (dialogs). */
class SecretFieldGuard internal constructor(private val scrub: (String) -> Unit, private val warningText: @Composable () -> Unit) {
    var rejected by mutableStateOf(false)
        private set

    /** The text to show: the input, or nothing when it looked like a secret. */
    fun filter(value: String): String {
        val secret = SecretGuard.isSecret(value)
        rejected = secret
        if (!secret) return value
        scrub(value)
        return ""
    }

    /** For `supportingText`. */
    val warning: (@Composable () -> Unit)? get() = if (rejected) warningText else null
}

@Composable
fun rememberSecretFieldGuard(): SecretFieldGuard {
    val scrub = rememberClipboardScrubber()
    return remember(scrub) { SecretFieldGuard(scrub) { SecretFieldWarning() } }
}

/** The explanation shown after a field was cleared. */
@Composable
fun SecretFieldWarning() {
    Text(stringResource(Res.string.secret_field_warning))
}
