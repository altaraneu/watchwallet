package com.altaran.watchwallet.feature.wallets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.altaran.watchwallet.core.data.chain.ChainParsers
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.DerivedAddress
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.ui.CopyButton
import com.altaran.watchwallet.feature.wallets.generated.resources.Res
import com.altaran.watchwallet.feature.wallets.generated.resources.address_copy
import com.altaran.watchwallet.feature.wallets.generated.resources.address_next_receive
import com.altaran.watchwallet.feature.wallets.generated.resources.detail_address
import com.altaran.watchwallet.feature.wallets.generated.resources.detail_address_cardano_hint
import com.altaran.watchwallet.feature.wallets.generated.resources.detail_address_evm_hint
import com.altaran.watchwallet.feature.wallets.generated.resources.detail_qr_description
import com.altaran.watchwallet.feature.wallets.generated.resources.detail_show_qr
import com.altaran.watchwallet.feature.wallets.generated.resources.detail_stake_key
import com.altaran.watchwallet.feature.wallets.generated.resources.detail_stake_key_hint
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** What the wallet can show as its address. */
internal data class ShownAddress(
    val value: String,
    val label: StringResource,
    val hint: StringResource? = null,
    /** `false` for values that cannot receive payments (a Cardano stake key). */
    val canReceive: Boolean = true,
)

/**
 * Only addresses the app can vouch for are offered for receiving money: what the user entered,
 * what was derived on this device from the extended key, or a Cardano address proven to carry the
 * wallet's stake key. The last one comes from a server, and a row stored by an older version was
 * never checked, so it is checked here on every display.
 */
internal fun shownAddress(target: WatchTarget, derived: List<DerivedAddress>): ShownAddress? = when (target) {
    is WatchTarget.ExtendedPublicKey -> {
        derived.firstOrNull { !it.change && !it.used }?.let { ShownAddress(it.address, Res.string.address_next_receive) }
    }

    is WatchTarget.Address if target.family == ChainFamily.CARDANO && target.address.startsWith("stake1") -> {
        derived.firstNotNullOfOrNull { ChainParsers.cardanoPaymentAddress(it.address, target.address) }
            ?.let { ShownAddress(it, Res.string.detail_address, Res.string.detail_address_cardano_hint) }
            ?: ShownAddress(target.address, Res.string.detail_stake_key, Res.string.detail_stake_key_hint, canReceive = false)
    }

    is WatchTarget.Address -> {
        val hint = if (target.family == ChainFamily.EVM) Res.string.detail_address_evm_hint else null
        ShownAddress(target.address, Res.string.detail_address, hint)
    }
}

/** The wallet's address with copy and QR actions, for example to receive a payment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletAddress(target: WatchTarget, derived: List<DerivedAddress>, modifier: Modifier = Modifier) {
    val shown = shownAddress(target, derived) ?: return
    var qrOpen by rememberSaveable { mutableStateOf(false) }
    val modules = remember(shown.value) { if (shown.canReceive) qrModules(shown.value) else null }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(shown.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(shown.value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        shown.hint?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CopyButton(text = shown.value, sensitive = true, label = stringResource(Res.string.address_copy), tonal = false)
            if (modules != null) {
                TextButton(onClick = { qrOpen = true }) { Text(stringResource(Res.string.detail_show_qr)) }
            }
        }
    }
    if (qrOpen && modules != null) {
        ModalBottomSheet(onDismissRequest = { qrOpen = false }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                QrCode(modules, stringResource(Res.string.detail_qr_description))
                SelectionContainer {
                    Text(shown.value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
                shown.hint?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CopyButton(text = shown.value, sensitive = true, label = stringResource(Res.string.address_copy))
            }
        }
    }
}

/** Always dark on white with a quiet zone, whatever the theme, so that every scanner can read it. */
@Composable
private fun QrCode(modules: List<BooleanArray>, description: String) {
    Canvas(
        Modifier
            .size(260.dp)
            .background(Color.White)
            .padding(16.dp)
            .semantics { contentDescription = description },
    ) {
        val cell = size.width / modules.size
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
