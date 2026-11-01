package com.altaran.watchwallet.feature.addwallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altaran.watchwallet.core.data.CandidatePreview
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.security.SecretKind
import com.altaran.watchwallet.core.ui.clipboard.plainText
import com.altaran.watchwallet.core.ui.familyLabel
import com.altaran.watchwallet.core.ui.format.MoneyFormatter
import com.altaran.watchwallet.core.ui.targetDescription
import com.altaran.watchwallet.feature.addwallet.generated.resources.Res
import com.altaran.watchwallet.feature.addwallet.generated.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun AddWalletRoute(
    onNavigateUp: () -> Unit,
    onWalletAdded: (Long) -> Unit,
    onBatchImport: (String) -> Unit = {},
    viewModel: AddWalletViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedWalletId) { state.savedWalletId?.let(onWalletAdded) }
    AddWalletScreen(
        state = state,
        chainName = viewModel::chainName,
        onNavigateUp = onNavigateUp,
        onInputChange = viewModel::onInputChange,
        onSubmit = viewModel::onSubmit,
        onSelectCandidate = viewModel::onSelectCandidate,
        onNameChange = viewModel::onNameChange,
        onSave = viewModel::onSave,
        onBatchImport = onBatchImport,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddWalletScreen(
    state: AddWalletUiState,
    chainName: (com.altaran.watchwallet.core.model.ChainId) -> String,
    onNavigateUp: () -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onSelectCandidate: (Int) -> Unit,
    onNameChange: (String) -> Unit,
    onSave: (defaultName: String) -> Unit,
    onBatchImport: (String) -> Unit = {},
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.add_wallet_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(WwIcon.ArrowBack.painter(), contentDescription = stringResource(Res.string.add_wallet_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(Res.string.add_wallet_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                label = { Text(stringResource(Res.string.add_wallet_input_label)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 4,
                // No autocorrect / suggestions: the keyboard must not learn or sync what is typed here.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = {
                    scope.launch {
                        val text = clipboard.getClipEntry()?.plainText()?.trim().orEmpty()
                        if (text.isNotEmpty()) onSubmit(text)
                    }
                }) {
                    Icon(WwIcon.Copy.painter(), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(Res.string.add_wallet_paste))
                }
                Button(onClick = { onSubmit(state.input) }, enabled = state.input.isNotBlank() && state.phase !is AddWalletPhase.Probing) {
                    Text(stringResource(Res.string.add_wallet_continue))
                }
            }

            when (val phase = state.phase) {
                AddWalletPhase.Idle -> Unit
                AddWalletPhase.Probing -> ProbingIndicator()
                is AddWalletPhase.SecretRejected -> SecretWarning(phase.kind)
                is AddWalletPhase.Unsupported -> InfoCard(
                    stringResource(Res.string.add_wallet_unsupported_title, familyLabel(phase.family)),
                    stringResource(Res.string.add_wallet_unsupported_message),
                )
                AddWalletPhase.WrongChecksum -> InfoCard(
                    stringResource(Res.string.add_wallet_checksum_title),
                    stringResource(Res.string.add_wallet_checksum_message),
                )
                AddWalletPhase.MultipleEntries -> {
                    InfoCard(
                        stringResource(Res.string.add_wallet_multiple_title),
                        stringResource(Res.string.add_wallet_multiple_message),
                    )
                    FilledTonalButton(onClick = { onBatchImport(state.input) }) { Text(stringResource(Res.string.add_wallet_open_batch)) }
                }
                AddWalletPhase.Unrecognized -> InfoCard(
                    stringResource(Res.string.add_wallet_unrecognized_title),
                    stringResource(Res.string.add_wallet_unrecognized_message),
                )
                is AddWalletPhase.Preview -> PreviewSection(phase, state, chainName, onSelectCandidate, onNameChange, onSave)
            }
        }
    }
}

@Composable
private fun ProbingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(stringResource(Res.string.add_wallet_probing), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SecretWarning(kind: SecretKind) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(WwIcon.Warning.painter(), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(if (kind == SecretKind.MNEMONIC) Res.string.secret_title_mnemonic else Res.string.secret_title_key),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(stringResource(Res.string.secret_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(stringResource(Res.string.secret_scam_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, message: String) {
    OutlinedCard {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(WwIcon.Info.painter(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PreviewSection(
    phase: AddWalletPhase.Preview,
    state: AddWalletUiState,
    chainName: (com.altaran.watchwallet.core.model.ChainId) -> String,
    onSelect: (Int) -> Unit,
    onNameChange: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (phase.previews.size > 1) {
            Text(stringResource(Res.string.add_wallet_pick_type), style = MaterialTheme.typography.titleSmall)
        }
        phase.previews.forEachIndexed { index, preview ->
            PreviewCard(preview, selected = index == phase.selected, showRadio = phase.previews.size > 1, chainName = chainName) { onSelect(index) }
        }
        val selected = phase.previews.getOrNull(phase.selected)
        val defaultName = selected?.let { defaultName(it.candidate.target) }.orEmpty()
        if (selected?.existingWalletId == null) {
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(Res.string.add_wallet_name_label)) },
                placeholder = { Text(defaultName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(onClick = { onSave(defaultName) }, enabled = !state.saving && selected != null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (selected?.existingWalletId != null) Res.string.add_wallet_open_existing else Res.string.add_wallet_save))
        }
    }
}

@Composable
private fun PreviewCard(
    preview: CandidatePreview,
    selected: Boolean,
    showRadio: Boolean,
    chainName: (com.altaran.watchwallet.core.model.ChainId) -> String,
    onClick: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    OutlinedCard(
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, border),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showRadio) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(12.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(targetDescription(preview.candidate.target), style = MaterialTheme.typography.titleMedium)
                Text(previewSummary(preview, chainName), style = MaterialTheme.typography.bodyMedium, color = summaryColor(preview))
            }
        }
    }
}

@Composable
private fun summaryColor(preview: CandidatePreview): Color =
    if (preview.probe?.hasActivity == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun previewSummary(preview: CandidatePreview, chainName: (com.altaran.watchwallet.core.model.ChainId) -> String): String {
    if (preview.existingWalletId != null) return stringResource(Res.string.add_wallet_already_tracked)
    val probe = preview.probe ?: return stringResource(Res.string.add_wallet_probe_failed)
    if (!probe.hasActivity) return stringResource(Res.string.add_wallet_no_activity)
    val parts = mutableListOf<String>()
    probe.usedAddressCount?.let { parts += pluralStringResource(Res.plurals.add_wallet_used_addresses, it, it) }
    if (preview.candidate.target.family == ChainFamily.EVM) {
        parts += stringResource(Res.string.add_wallet_active_on, probe.activeChains.joinToString(", ") { chainName(it) })
    } else {
        probe.transactionCount?.let { parts += pluralStringResource(Res.plurals.add_wallet_transactions, it, it) }
    }
    probe.balances.filter { !it.amount.isZero }.groupBy { it.asset.symbol }.forEach { (symbol, list) ->
        parts += MoneyFormatter.formatCrypto(list.sumOf { it.amount.toDouble() }, symbol)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun defaultName(target: WatchTarget): String = when {
    target.family == ChainFamily.EVM -> stringResource(Res.string.default_name_evm)
    target is WatchTarget.ExtendedPublicKey -> stringResource(Res.string.default_name_bitcoin_wallet)
    else -> stringResource(Res.string.default_name_bitcoin_address)
}
