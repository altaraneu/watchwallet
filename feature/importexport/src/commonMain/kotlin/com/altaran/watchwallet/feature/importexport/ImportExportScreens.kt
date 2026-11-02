package com.altaran.watchwallet.feature.importexport

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altaran.watchwallet.core.designsystem.component.SectionHeader
import com.altaran.watchwallet.core.designsystem.component.SettingsSwitchItem
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.importexport.EntryStatus
import com.altaran.watchwallet.core.ui.familyLabel
import com.altaran.watchwallet.core.ui.secret.SecretFieldWarning
import com.altaran.watchwallet.core.ui.secret.SensitiveInput
import com.altaran.watchwallet.core.ui.secret.rememberClipboardScrubber
import com.altaran.watchwallet.core.ui.shortAddress
import com.altaran.watchwallet.core.ui.targetDescription
import com.altaran.watchwallet.feature.importexport.generated.resources.*
import com.altaran.watchwallet.feature.importexport.generated.resources.Res
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenScaffold(title: String, onNavigateUp: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onNavigateUp) { Icon(WwIcon.ArrowBack.painter(), stringResource(Res.string.ie_back)) } },
            )
        },
    ) { padding -> content(Modifier.padding(padding).imePadding()) }
}

@Composable
internal fun ImportRoute(onNavigateUp: () -> Unit, onDone: () -> Unit, viewModel: ImportViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val documents = rememberDocumentActions()
    val scrubClipboard = rememberClipboardScrubber()
    ScreenScaffold(stringResource(Res.string.import_title), onNavigateUp) { modifier ->
        when (val phase = state.phase) {
            ImportPhase.Input, is ImportPhase.SecretRejected -> Column(
                modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(Res.string.import_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (phase is ImportPhase.SecretRejected) SecretCard(phase.kind)
                SensitiveInput {
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = { text -> if (viewModel.onTextChange(text)) scrubClipboard(text) },
                        label = { Text(stringResource(Res.string.import_paste_label)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 6,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { documents.openText { text -> text?.let(viewModel::parse) } }) {
                        Text(stringResource(Res.string.import_choose_file))
                    }
                    Button(onClick = { viewModel.parse() }, enabled = state.text.isNotBlank()) { Text(stringResource(Res.string.import_preview)) }
                }
            }

            ImportPhase.NeedsPassword, ImportPhase.WrongPassword -> PasswordPrompt(
                wrong = phase == ImportPhase.WrongPassword,
                onSubmit = viewModel::decrypt,
                modifier = modifier,
            )

            is ImportPhase.Preview -> PreviewList(phase, state.groupName, state.groupRejected, viewModel, modifier)

            ImportPhase.Importing, ImportPhase.Decrypting -> Column(
                modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }

            is ImportPhase.Done -> Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.import_done_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.import_done_summary, phase.summary.imported, phase.summary.skipped, phase.summary.failed))
                Text(
                    stringResource(Res.string.import_done_sync),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onDone) { Text(stringResource(Res.string.import_done_action)) }
            }
        }
    }
}

@Composable
private fun SecretCard(kind: com.altaran.watchwallet.core.security.SecretKind) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(WwIcon.Warning.painter(), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(Res.string.import_secret_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    stringResource(Res.string.import_secret_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (kind == com.altaran.watchwallet.core.security.SecretKind.HEX_PRIVATE_KEY) {
                    Text(
                        stringResource(Res.string.import_secret_hex_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun PasswordPrompt(wrong: Boolean, onSubmit: (String) -> Unit, modifier: Modifier) {
    var password by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(Res.string.import_encrypted), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(Res.string.export_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = wrong,
            supportingText = if (wrong) ({ Text(stringResource(Res.string.import_wrong_password)) }) else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) { Text(stringResource(Res.string.import_decrypt)) }
    }
}

@Composable
private fun PreviewList(phase: ImportPhase.Preview, groupName: String, groupRejected: Boolean, viewModel: ImportViewModel, modifier: Modifier) {
    val selected = phase.rows.count { it.mayImport }
    val scrubClipboard = rememberClipboardScrubber()
    var confirming by remember { mutableStateOf<Int?>(null) }
    confirming?.let { index ->
        val family = phase.rows.getOrNull(index)?.entry?.candidates?.firstOrNull()?.family
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(Res.string.import_confirm_title)) },
            text = { Text(stringResource(Res.string.import_confirm_message, family?.let { familyLabel(it) }.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    viewModel.confirm(index)
                }) { Text(stringResource(Res.string.import_confirm_accept)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(Res.string.import_confirm_cancel)) } },
        )
    }
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                SensitiveInput {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { text -> if (viewModel.onGroupNameChange(text)) scrubClipboard(text) },
                        label = { Text(stringResource(Res.string.import_group)) },
                        isError = groupRejected,
                        supportingText = if (groupRejected) ({ SecretFieldWarning() }) else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
            itemsIndexed(phase.rows) { index, row ->
                val selectable = ImportRows.canToggle(row)
                // One touch target and one semantics node per row: the row toggles, the checkbox
                // only shows the state.
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(value = row.selected, enabled = selectable, role = Role.Checkbox) { viewModel.toggle(index) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = row.selected,
                        onCheckedChange = null,
                        enabled = selectable,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.entry.label ?: shortAddress(row.entry.raw),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val target = row.entry.candidates.getOrNull(row.chosen)
                        Text(
                            when {
                                row.alreadyTracked -> stringResource(Res.string.import_status_tracked)

                                row.entry.status == EntryStatus.DUPLICATE -> stringResource(Res.string.import_status_duplicate)

                                row.entry.status == EntryStatus.UNSUPPORTED -> stringResource(
                                    Res.string.import_status_unsupported,
                                    row.entry.unsupportedFamily?.let {
                                        familyLabel(it)
                                    }.orEmpty(),
                                )

                                row.needsConfirmation -> stringResource(Res.string.import_status_confirm)

                                row.entry.status == EntryStatus.INVALID -> stringResource(Res.string.import_status_invalid)

                                target != null -> row.entry.group?.let { group ->
                                    stringResource(Res.string.import_row_target_group, targetDescription(target), group)
                                } ?: targetDescription(target)

                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (row.needsConfirmation && !row.alreadyTracked) {
                            TextButton(onClick = { confirming = index }) { Text(stringResource(Res.string.import_confirm_action)) }
                        }
                        if (row.entry.status == EntryStatus.AMBIGUOUS) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.entry.candidates.forEachIndexed { i, candidate ->
                                    FilterChip(selected = row.chosen == i, onClick = { viewModel.choose(index, i) }, label = {
                                        Text(
                                            (candidate as? com.altaran.watchwallet.core.model.WatchTarget.ExtendedPublicKey)?.scriptType?.let {
                                                com.altaran.watchwallet.core.ui.scriptTypeLabel(it)
                                            }
                                                ?: "?",
                                        )
                                    })
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        Button(onClick = viewModel::import, enabled = selected > 0, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(pluralStringResource(Res.plurals.import_action, selected, selected))
        }
    }
}

@Composable
internal fun ExportRoute(onNavigateUp: () -> Unit, viewModel: ExportViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val documents = rememberDocumentActions(viewModel::takePendingSave, viewModel::onSaveFinished)
    // The view model builds (and encrypts) in the background; the platform action runs here.
    LaunchedEffect(state.prepared) {
        val prepared = state.prepared ?: return@LaunchedEffect
        viewModel.onPreparedHandled()
        if (!prepared.share) {
            documents.createDocument(prepared.fileName, prepared.mimeType)
        } else if (!documents.share(prepared.fileName, prepared.mimeType, prepared.content)) {
            viewModel.showMessage(ExportMessage.SAVE_FAILED)
        }
    }
    ScreenScaffold(stringResource(Res.string.export_title), onNavigateUp) { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.padding(16.dp)) {
                Text(stringResource(Res.string.export_notice), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            SectionHeader(stringResource(Res.string.export_scope))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.groupId == null, onClick = {
                    viewModel.setGroup(null)
                }, label = { Text(stringResource(Res.string.export_all, state.walletCount)) })
                state.groups.forEach { group ->
                    FilterChip(selected = state.groupId == group.id, onClick = { viewModel.setGroup(group.id) }, label = { Text(group.name) })
                }
            }
            SectionHeader(stringResource(Res.string.export_format))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormatOption.entries.forEach { format ->
                    FilterChip(selected = state.format == format, onClick = { viewModel.setFormat(format) }, label = {
                        Text(
                            stringResource(
                                when (format) {
                                    ExportFormatOption.JSON -> Res.string.export_format_json
                                    ExportFormatOption.CSV -> Res.string.export_format_csv
                                    ExportFormatOption.TEXT -> Res.string.export_format_text
                                    ExportFormatOption.TRANSACTIONS -> Res.string.export_format_transactions
                                },
                            ),
                        )
                    })
                }
            }
            when (state.format) {
                ExportFormatOption.JSON -> if (ExportCrypto.isSupported) ExportPasswordFields(state, viewModel)

                ExportFormatOption.CSV -> SettingsSwitchItem(
                    title = stringResource(Res.string.export_include_values),
                    checked = state.includeValues,
                    onCheckedChange = viewModel::setIncludeValues,
                )

                ExportFormatOption.TEXT -> Unit

                ExportFormatOption.TRANSACTIONS -> Text(
                    stringResource(Res.string.export_transactions_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.message?.let {
                Text(
                    stringResource(if (it == ExportMessage.SAVED) Res.string.export_saved else Res.string.export_save_failed),
                    color = if (it == ExportMessage.SAVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            FlowRow(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val canExport = state.walletCount > 0 && !state.busy && state.passwordProblem == null
                Button(onClick = { viewModel.prepare(share = false) }, enabled = canExport) { Text(stringResource(Res.string.export_save)) }
                OutlinedButton(onClick = { viewModel.prepare(share = true) }, enabled = canExport) {
                    Text(stringResource(Res.string.export_share))
                }
                if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}

@Composable
private fun ExportPasswordFields(state: ExportUiState, viewModel: ExportViewModel) {
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::setPassword,
        label = { Text(stringResource(Res.string.export_password)) },
        supportingText = {
            Column {
                Text(stringResource(Res.string.export_password_hint))
                Text(pluralStringResource(Res.plurals.export_password_min_length, MIN_EXPORT_PASSWORD_LENGTH, MIN_EXPORT_PASSWORD_LENGTH))
            }
        },
        isError = state.passwordProblem == PasswordProblem.TOO_SHORT,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        singleLine = true,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp),
    )
    // Not while the first field is still empty: without a password the backup is simply not encrypted.
    if (state.password.isNotEmpty() || state.passwordConfirmation.isNotEmpty()) {
        val mismatch = state.passwordProblem == PasswordProblem.MISMATCH && state.passwordConfirmation.isNotEmpty()
        OutlinedTextField(
            value = state.passwordConfirmation,
            onValueChange = viewModel::setPasswordConfirmation,
            label = { Text(stringResource(Res.string.export_password_confirm)) },
            supportingText = if (mismatch) ({ Text(stringResource(Res.string.export_password_mismatch)) }) else null,
            isError = mismatch,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp),
        )
    }
}
