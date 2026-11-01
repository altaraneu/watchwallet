package com.altaran.watchwallet.core.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.ui.clipboard.plainTextClipEntry
import com.altaran.watchwallet.core.ui.generated.resources.Res
import com.altaran.watchwallet.core.ui.generated.resources.copy_action
import com.altaran.watchwallet.core.ui.generated.resources.copy_done
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private const val COPIED_CONFIRMATION_MS = 2_000L

/**
 * Copies [text] to the clipboard and confirms it for a moment ("Copied" with a check icon),
 * then returns to its normal label. The confirmation is announced through a polite live region.
 *
 * @param sensitive marks the clip as sensitive (tracked addresses); public donation addresses
 * do not need it.
 * @param label visible label of the button in its normal state.
 * @param contentDescription screen reader label in the normal state when [label] alone is
 * ambiguous, for example "Copy Bitcoin (BTC) address".
 * @param tonal filled tonal button when `true`, a text button otherwise.
 */
@Composable
fun CopyButton(
    text: String,
    sensitive: Boolean,
    modifier: Modifier = Modifier,
    label: String = stringResource(Res.string.copy_action),
    contentDescription: String? = null,
    tonal: Boolean = true,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_CONFIRMATION_MS)
            copied = false
        }
    }

    val onClick: () -> Unit = {
        scope.launch {
            clipboard.setClipEntry(plainTextClipEntry(text, sensitive = sensitive))
            copied = true
        }
    }
    val buttonModifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    val content: @Composable RowScope.() -> Unit = {
        Icon(
            painter = (if (copied) WwIcon.Check else WwIcon.Copy).painter(),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(
            text = if (copied) stringResource(Res.string.copy_done) else label,
            modifier = if (copied || contentDescription == null) {
                Modifier
            } else {
                Modifier.semantics { this.contentDescription = contentDescription }
            },
        )
    }
    if (tonal) {
        FilledTonalButton(onClick = onClick, modifier = buttonModifier, content = content)
    } else {
        TextButton(onClick = onClick, modifier = buttonModifier, content = content)
    }
}
