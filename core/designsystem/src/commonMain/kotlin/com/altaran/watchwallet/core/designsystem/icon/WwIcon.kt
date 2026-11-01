package com.altaran.watchwallet.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.altaran.watchwallet.core.designsystem.generated.resources.Res
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_add
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_arrow_back
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_check
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_chevron_right
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_code
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_copy
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_favorite
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_info
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_markets
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_paste
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_portfolio
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_settings
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_visibility
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_visibility_off
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_wallets
import com.altaran.watchwallet.core.designsystem.generated.resources.ic_warning
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * The app's icon set. Feature modules use these instead of shipping their own icons,
 * which keeps the visual language consistent.
 */
enum class WwIcon(internal val resource: DrawableResource) {
    Portfolio(Res.drawable.ic_portfolio),
    Wallets(Res.drawable.ic_wallets),
    Markets(Res.drawable.ic_markets),
    Settings(Res.drawable.ic_settings),
    Copy(Res.drawable.ic_copy),
    Paste(Res.drawable.ic_paste),
    Check(Res.drawable.ic_check),
    Visibility(Res.drawable.ic_visibility),
    VisibilityOff(Res.drawable.ic_visibility_off),
    Add(Res.drawable.ic_add),
    ChevronRight(Res.drawable.ic_chevron_right),
    ArrowBack(Res.drawable.ic_arrow_back),
    Favorite(Res.drawable.ic_favorite),
    Code(Res.drawable.ic_code),
    Info(Res.drawable.ic_info),
    Warning(Res.drawable.ic_warning),
}

@Composable
fun WwIcon.painter(): Painter = painterResource(resource)
