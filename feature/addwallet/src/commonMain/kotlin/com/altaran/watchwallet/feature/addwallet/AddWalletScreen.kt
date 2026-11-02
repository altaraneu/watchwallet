package com.altaran.watchwallet.feature.addwallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.altaran.watchwallet.core.ui.secret.SecretFieldWarning
import com.altaran.watchwallet.core.ui.secret.SensitiveInput
import com.altaran.watchwallet.core.ui.secret.rememberClipboardScrubber
import com.altaran.watchwallet.core.ui.targetDescription
import com.altaran.watchwallet.feature.addwallet.generated.resources.*
import com.altaran.watchwallet.feature.addwallet.generated.resources.Res
import com.altaran.watchwallet.feature.addwallet.generated.resources.add_wallet_group_hint
import com.altaran.watchwallet.feature.addwallet.generated.resources.add_wallet_group_label
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
        onPaste = viewModel::onPaste,
        onSelectCandidate = viewModel::onSelectCandidate,
        onNameChange = viewModel::onNameChange,
        onSave = viewModel::onSave,
        onAdd = { text -> viewModel.onSubmit(text, saveWhenUnambiguous = true) },
        onGroupChange = viewModel::onGroupChange,
        onBatchImport = onBatchImport,
        onScanned = viewModel::onScanned,
        onStartDeliberate = viewModel::onStartDeliberate,
        onPickDeliberateChain = viewModel::onPickDeliberateChain,
        onAcceptDeliberateWarning = viewModel::onAcceptDeliberateWarning,
        onConfirmUnused = viewModel::onConfirmUnused,
        onResolveOverlap = viewModel::onResolveOverlap,
        onClear = viewModel::onClear,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddWalletScreen(
    state: AddWalletUiState,
    chainName: (com.altaran.watchwallet.core.model.ChainId) -> String,
    onNavigateUp: () -> Unit,
    /** Returns `true` when the text was rejected as key material. */
    onInputChange: (String) -> Boolean,
    onSubmit: (String) -> Unit,
    /** Submits pasted text; returns `true` when it was rejected as key material. */
    onPaste: (String) -> Boolean = {
        onSubmit(it)
        false
    },
    onSelectCandidate: (Int) -> Unit,
    onAdd: (String) -> Unit = onSubmit,
    onGroupChange: (String) -> Boolean = { false },
    onNameChange: (String) -> Boolean,
    onSave: (defaultName: String) -> Unit,
    onBatchImport: (String) -> Unit = {},
    onScanned: (String, ByteArray?) -> Unit = { text, _ -> onSubmit(text) },
    onStartDeliberate: () -> Unit = {},
    onPickDeliberateChain: (ChainFamily) -> Unit = {},
    onAcceptDeliberateWarning: () -> Unit = {},
    onConfirmUnused: () -> Unit = {},
    onResolveOverlap: (Boolean) -> Unit = {},
    onClear: () -> Unit = {},
) {
    val scrubClipboard = rememberClipboardScrubber()
    // Whatever the source of the text (keyboard, system paste, drag): a rejected value also leaves the clipboard.
    val guarded: ((String) -> Boolean) -> (String) -> Unit = { change -> { text -> if (change(text)) scrubClipboard(text) } }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Hide the keyboard on submit: the preview and its "Add wallet" button must be visible.
    val submit: (String) -> Unit = { text ->
        keyboard?.hide()
        focusManager.clearFocus()
        onSubmit(text)
    }
    val preview = state.phase as? AddWalletPhase.Preview
    val selectedPreview = preview?.previews?.getOrNull(preview.selected)
    val saveName = selectedPreview?.let { defaultName(it.candidate.target) }.orEmpty()
    val save: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus()
        onSave(saveName)
    }
    // One-tap add: save as soon as an unambiguous preview is ready.
    LaunchedEffect(selectedPreview, state.autoSave) {
        if (state.autoSave && selectedPreview != null && !state.saving) save()
    }
    var scanning by rememberSaveable { mutableStateOf(false) }
    if (scanning) {
        QrScannerDialog(onResult = { text, bytes ->
            scanning = false
            keyboard?.hide()
            focusManager.clearFocus()
            onScanned(text, bytes)
        }, onDismiss = { scanning = false })
    }
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
            SensitiveInput {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = guarded(onInputChange),
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
                    keyboardActions = KeyboardActions(onDone = { if (state.input.isNotBlank()) submit(state.input) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            GroupField(state.groupName, state.groups, state.fieldRejected, guarded(onGroupChange))
            InputActions(
                onPaste = {
                    scope.launch {
                        val text = clipboard.getClipEntry()?.plainText()?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            keyboard?.hide()
                            focusManager.clearFocus()
                            // A seed phrase or key must not stay on the clipboard, where other
                            // apps and keyboards can read it. The warning stays on screen.
                            if (onPaste(text)) clipboard.setClipEntry(null)
                        }
                    }
                },
                onScan = { scanning = true },
                // Once the preview is ready, its own button (below the preview) saves.
                showPrimary = selectedPreview == null,
                primaryEnabled = state.input.isNotBlank() && state.phase !is AddWalletPhase.Probing,
                onPrimary = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    onAdd(state.input)
                },
            )
            val deliberate =
                state.phase is AddWalletPhase.DeliberateChain || state.phase is AddWalletPhase.DeliberateWarning ||
                    state.phase is AddWalletPhase.DeliberateEntry
            if (!deliberate) {
                androidx.compose.material3.TextButton(onClick = onStartDeliberate) { Text(stringResource(Res.string.add_sui_aptos_action)) }
            }

            PhaseContent(
                state = state,
                chainName = chainName,
                onSelectCandidate = onSelectCandidate,
                onNameChange = guarded(onNameChange),
                onSave = { save() },
                onBatchImport = onBatchImport,
                onPickDeliberateChain = onPickDeliberateChain,
                onAcceptDeliberateWarning = onAcceptDeliberateWarning,
                onConfirmUnused = onConfirmUnused,
                onResolveOverlap = onResolveOverlap,
                onClear = onClear,
            )
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
                if (kind == SecretKind.HEX_PRIVATE_KEY) {
                    Text(
                        stringResource(Res.string.secret_hex_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Text(
                    stringResource(Res.string.secret_scam_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun WarningCard(title: String, message: String, actions: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(WwIcon.Warning.painter(), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
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
            SensitiveInput {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(Res.string.add_wallet_name_label)) },
                    placeholder = { Text(defaultName) },
                    isError = state.fieldRejected,
                    supportingText = if (state.fieldRejected) ({ SecretFieldWarning() }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(defaultName) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
    target.chainId() == "bitcoin" && target is WatchTarget.ExtendedPublicKey -> stringResource(Res.string.default_name_bitcoin_wallet)
    target.chainId() == "bitcoin" -> stringResource(Res.string.default_name_bitcoin_address)
    else -> stringResource(Res.string.default_name_generic, com.altaran.watchwallet.core.ui.chainLabel(target))
}

private fun WatchTarget.chainId(): String = when (this) {
    is WatchTarget.Address -> chainId.value
    is WatchTarget.ExtendedPublicKey -> chainId.value
}

@Composable
private fun InputActions(
    onPaste: () -> Unit,
    onScan: () -> Unit,
    showPrimary: Boolean,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onPaste) {
            Icon(WwIcon.Paste.painter(), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(Res.string.add_wallet_paste))
        }
        if (isQrScanningSupported) {
            FilledTonalButton(onClick = onScan) { Text(stringResource(Res.string.add_wallet_scan)) }
        }
        if (showPrimary) {
            Button(onClick = onPrimary, enabled = primaryEnabled) { Text(stringResource(Res.string.add_wallet_save)) }
        }
    }
}

/** What follows the input: checking, warnings, or the preview to confirm. */
@Composable
private fun PhaseContent(
    state: AddWalletUiState,
    chainName: (com.altaran.watchwallet.core.model.ChainId) -> String,
    onSelectCandidate: (Int) -> Unit,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onBatchImport: (String) -> Unit,
    onPickDeliberateChain: (ChainFamily) -> Unit,
    onAcceptDeliberateWarning: () -> Unit,
    onConfirmUnused: () -> Unit,
    onResolveOverlap: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    when (val phase = state.phase) {
        AddWalletPhase.Idle -> {
            Unit
        }

        AddWalletPhase.Probing -> {
            ProbingIndicator()
        }

        is AddWalletPhase.SecretRejected -> {
            SecretWarning(phase.kind)
        }

        is AddWalletPhase.Unsupported -> {
            InfoCard(
                stringResource(Res.string.add_wallet_unsupported_title, familyLabel(phase.family)),
                stringResource(Res.string.add_wallet_unsupported_message),
            )
        }

        is AddWalletPhase.DeliberateChain -> {
            InfoCard(stringResource(Res.string.deliberate_pick_title), stringResource(Res.string.deliberate_pick_message))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                phase.options.forEach { family ->
                    FilledTonalButton(onClick = { onPickDeliberateChain(family) }) { Text(familyLabel(family)) }
                }
                androidx.compose.material3.TextButton(onClick = onClear) { Text(stringResource(Res.string.confirm_clear)) }
            }
        }

        is AddWalletPhase.DeliberateWarning -> {
            WarningCard(
                title = stringResource(Res.string.deliberate_warning_title),
                message = stringResource(Res.string.deliberate_warning_message, familyLabel(phase.family)),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton(onClick = onClear) { Text(stringResource(Res.string.confirm_clear)) }
                    FilledTonalButton(onClick = onAcceptDeliberateWarning) { Text(stringResource(Res.string.deliberate_accept)) }
                }
            }
        }

        is AddWalletPhase.DeliberateEntry -> {
            InfoCard(
                stringResource(Res.string.deliberate_entry_title, familyLabel(phase.family)),
                stringResource(if (phase.invalid) Res.string.deliberate_entry_invalid else Res.string.deliberate_entry_message),
            )
            androidx.compose.material3.TextButton(onClick = onClear) { Text(stringResource(Res.string.confirm_clear)) }
        }

        is AddWalletPhase.ConfirmUnused -> {
            WarningCard(
                title = stringResource(Res.string.confirm_unused_title),
                message = stringResource(Res.string.confirm_unused_message),
            ) {
                androidx.compose.material3.TextButton(onClick = onClear) { Text(stringResource(Res.string.confirm_clear)) }
                FilledTonalButton(onClick = onConfirmUnused) { Text(stringResource(Res.string.confirm_add_anyway)) }
            }
        }

        is AddWalletPhase.ConfirmOverlap -> {
            val owner = phase.preview.containingWallet?.name.orEmpty()
            WarningCard(
                title = stringResource(Res.string.overlap_title, owner),
                message = stringResource(Res.string.overlap_message, owner),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton(onClick = onClear, enabled = !state.saving) { Text(stringResource(Res.string.confirm_clear)) }
                    androidx.compose.material3.TextButton(onClick = { onResolveOverlap(false) }, enabled = !state.saving) {
                        Text(stringResource(Res.string.overlap_keep_both))
                    }
                    FilledTonalButton(onClick = { onResolveOverlap(true) }, enabled = !state.saving) { Text(stringResource(Res.string.overlap_subtract)) }
                }
            }
        }

        AddWalletPhase.WrongChecksum -> {
            InfoCard(
                stringResource(Res.string.add_wallet_checksum_title),
                stringResource(Res.string.add_wallet_checksum_message),
            )
        }

        AddWalletPhase.MultipleEntries -> {
            InfoCard(
                stringResource(Res.string.add_wallet_multiple_title),
                stringResource(Res.string.add_wallet_multiple_message),
            )
            FilledTonalButton(onClick = { onBatchImport(state.input) }) { Text(stringResource(Res.string.add_wallet_open_batch)) }
        }

        AddWalletPhase.Unrecognized -> {
            InfoCard(
                stringResource(Res.string.add_wallet_unrecognized_title),
                stringResource(Res.string.add_wallet_unrecognized_message),
            )
        }

        is AddWalletPhase.Preview -> {
            PreviewSection(phase, state, chainName, onSelectCandidate, onNameChange, onSave = { onSave() })
        }
    }
}

/** Optional group (whose wallet: "Partner", "Me", "Savings"…), with existing groups as chips. */
@Composable
private fun GroupField(value: String, groups: List<String>, rejected: Boolean, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SensitiveInput {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(stringResource(Res.string.add_wallet_group_label)) },
                placeholder = { Text(stringResource(Res.string.add_wallet_group_hint)) },
                isError = rejected,
                supportingText = if (rejected) ({ SecretFieldWarning() }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (groups.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { group ->
                    FilterChip(selected = value == group, onClick = { onChange(if (value == group) "" else group) }, label = { Text(group) })
                }
            }
        }
    }
}
