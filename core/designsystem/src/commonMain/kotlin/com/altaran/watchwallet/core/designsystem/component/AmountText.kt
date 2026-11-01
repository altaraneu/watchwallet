package com.altaran.watchwallet.core.designsystem.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.altaran.watchwallet.core.designsystem.generated.resources.Res
import com.altaran.watchwallet.core.designsystem.generated.resources.amount_hidden
import com.altaran.watchwallet.core.designsystem.theme.tabularNumbers
import org.jetbrains.compose.resources.stringResource

/**
 * Discreet mode: when `true`, every [AmountText] hides its value. Provided once at the root
 * of the UI from the user's settings, so no screen can forget to respect it.
 */
val LocalDiscreetMode = staticCompositionLocalOf { false }

private const val HIDDEN_AMOUNT = "•••••"

/**
 * Displays an already formatted monetary amount or balance.
 *
 * All amounts in the app must go through this component so that discreet mode,
 * tabular figures and accessibility are handled in one place.
 */
@Composable
fun AmountText(
    formattedAmount: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    discreet: Boolean = LocalDiscreetMode.current,
) {
    val hiddenDescription = stringResource(Res.string.amount_hidden)
    Text(
        text = if (discreet) HIDDEN_AMOUNT else formattedAmount,
        modifier = if (discreet) {
            modifier.semantics { contentDescription = hiddenDescription }
        } else {
            modifier
        },
        style = style.tabularNumbers(),
        color = color,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
