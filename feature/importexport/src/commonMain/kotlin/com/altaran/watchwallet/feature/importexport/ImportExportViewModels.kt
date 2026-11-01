package com.altaran.watchwallet.feature.importexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.data.repository.PortfolioRepository
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.importexport.BatchEntry
import com.altaran.watchwallet.core.importexport.BatchParser
import com.altaran.watchwallet.core.importexport.BatchResult
import com.altaran.watchwallet.core.importexport.EntryStatus
import com.altaran.watchwallet.core.importexport.ExportFormat
import com.altaran.watchwallet.core.importexport.ExportedGroup
import com.altaran.watchwallet.core.importexport.ExportedWallet
import com.altaran.watchwallet.core.importexport.WatchWalletExport
import com.altaran.watchwallet.core.model.WalletGroup
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.security.SecretKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** A preview row with the user's choices (selected, chosen candidate for ambiguous rows). */
data class ImportRow(
    val entry: BatchEntry,
    val selected: Boolean,
    val chosen: Int = 0,
    val alreadyTracked: Boolean = false,
)

data class ImportSummary(val imported: Int, val skipped: Int, val failed: Int)

sealed interface ImportPhase {
    data object Input : ImportPhase
    data class SecretRejected(val kind: SecretKind, val line: Int) : ImportPhase
    data object NeedsPassword : ImportPhase
    data object WrongPassword : ImportPhase
    data class Preview(val rows: List<ImportRow>) : ImportPhase
    data object Importing : ImportPhase
    data class Done(val summary: ImportSummary) : ImportPhase
}

data class ImportUiState(
    val text: String = "",
    val phase: ImportPhase = ImportPhase.Input,
    /** Optional group for all imported wallets (rows with their own group keep it). */
    val groupName: String = "",
)

internal class ImportViewModel(
    private val walletRepository: WalletRepository,
    private val syncEngine: SyncEngine,
    private val addWalletService: com.altaran.watchwallet.core.data.AddWalletService,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()
    private var encrypted: String? = null

    fun onTextChange(value: String) = _state.update { it.copy(text = value, phase = ImportPhase.Input) }

    fun onGroupNameChange(value: String) = _state.update { it.copy(groupName = value) }

    /** Parses pasted or loaded text. Secrets abort the import and the input is discarded. */
    fun parse(text: String = _state.value.text) {
        if (ExportCrypto.isEncrypted(text)) {
            encrypted = text
            _state.update { it.copy(text = "", phase = ImportPhase.NeedsPassword) }
            return
        }
        when (val result = BatchParser.parse(text, addWalletService::parseOtherChains)) {
            is BatchResult.SecretDetected -> _state.value = ImportUiState(phase = ImportPhase.SecretRejected(result.kind, result.line))

            BatchResult.Empty -> _state.update { it.copy(text = text, phase = ImportPhase.Input) }

            is BatchResult.Entries -> viewModelScope.launch {
                val rows = result.entries.map { entry ->
                    val tracked = entry.candidates.firstOrNull()?.let { walletRepository.findExisting(it) } != null
                    ImportRow(entry, selected = entry.status in IMPORTABLE && !tracked, alreadyTracked = tracked)
                }
                _state.update { it.copy(text = text, phase = ImportPhase.Preview(rows)) }
            }
        }
    }

    fun decrypt(password: String) {
        val envelope = encrypted ?: return
        val plain = ExportCrypto.decrypt(envelope, password)
        if (plain == null) {
            _state.update { it.copy(phase = ImportPhase.WrongPassword) }
        } else {
            encrypted = null
            parse(plain)
        }
    }

    fun toggle(index: Int) = updateRows { rows -> rows.mapIndexed { i, r -> if (i == index) r.copy(selected = !r.selected) else r } }

    fun choose(index: Int, candidate: Int) = updateRows { rows ->
        rows.mapIndexed { i, r ->
            if (i ==
                index
            ) {
                r.copy(chosen = candidate, selected = true)
            } else {
                r
            }
        }
    }

    fun import() {
        val phase = _state.value.phase as? ImportPhase.Preview ?: return
        val commonGroup = _state.value.groupName.trim().ifBlank { null }
        _state.update { it.copy(phase = ImportPhase.Importing) }
        viewModelScope.launch {
            var imported = 0
            var skipped = 0
            var failed = 0
            val groupIds = mutableMapOf<String, Long>()
            suspend fun groupId(name: String?): Long? = name?.let {
                groupIds.getOrPut(it) { walletRepository.findGroup(it) ?: walletRepository.createGroup(it) }
            }
            phase.rows.forEach { row ->
                val target = row.entry.candidates.getOrNull(row.chosen)
                when {
                    !row.selected -> skipped++

                    target == null -> failed++

                    walletRepository.findExisting(target) != null -> skipped++

                    else -> runCatching {
                        walletRepository.addWallet(target, row.entry.label ?: defaultName(target), emptyList(), groupId(row.entry.group ?: commonGroup))
                    }.onSuccess { imported++ }.onFailure { failed++ }
                }
            }
            // Probing and the first sync run in the background (bounded parallelism, rate limited).
            syncEngine.syncAll()
            _state.update { it.copy(phase = ImportPhase.Done(ImportSummary(imported, skipped, failed))) }
        }
    }

    private fun defaultName(target: WatchTarget): String = when (target) {
        is WatchTarget.Address -> target.address.take(6) + "…" + target.address.takeLast(4)
        is WatchTarget.ExtendedPublicKey -> target.key.take(6) + "…" + target.key.takeLast(4)
    }

    private fun updateRows(transform: (List<ImportRow>) -> List<ImportRow>) = _state.update { state ->
        val phase = state.phase as? ImportPhase.Preview ?: return@update state
        state.copy(phase = phase.copy(rows = transform(phase.rows)))
    }

    private companion object {
        val IMPORTABLE = setOf(EntryStatus.READY, EntryStatus.AMBIGUOUS)
    }
}

enum class ExportFormatOption { JSON, CSV, TEXT }

data class ExportUiState(
    val groups: List<WalletGroup> = emptyList(),
    /** `null` = all wallets. */
    val groupId: Long? = null,
    val format: ExportFormatOption = ExportFormatOption.JSON,
    val includeValues: Boolean = false,
    val password: String = "",
    val walletCount: Int = 0,
    val message: ExportMessage? = null,
)

enum class ExportMessage { SAVED, SAVE_FAILED }

internal class ExportViewModel(
    private val walletRepository: WalletRepository,
    private val portfolioRepository: PortfolioRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    val groups: StateFlow<List<WalletGroup>> = walletRepository.groups.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            walletRepository.groups.collect { groups -> _state.update { it.copy(groups = groups) } }
        }
        viewModelScope.launch {
            walletRepository.activeWallets.collect { wallets -> _state.update { it.copy(walletCount = wallets.size) } }
        }
    }

    fun setGroup(id: Long?) = _state.update { it.copy(groupId = id) }
    fun setFormat(format: ExportFormatOption) = _state.update { it.copy(format = format) }
    fun setIncludeValues(value: Boolean) = _state.update { it.copy(includeValues = value) }
    fun setPassword(value: String) = _state.update { it.copy(password = value) }
    fun showMessage(message: ExportMessage?) = _state.update { it.copy(message = message) }

    /** Builds the export file content and a suggested file name. */
    suspend fun build(): Pair<String, String> {
        val state = _state.value
        val groups = walletRepository.groups.first()
        val groupNames = groups.associate { it.id to it.name }
        val wallets = walletRepository.activeWallets.first()
            .filter { state.groupId == null || it.groupId == state.groupId }
            .mapNotNull { wallet ->
                wallet.target?.let { ExportedWallet.from(it, wallet.name, wallet.groupId?.let(groupNames::get), wallet.notes) }
            }
        val stamp = Clock.System.now().toString().take(10)
        return when (state.format) {
            ExportFormatOption.JSON -> {
                val usedGroups = wallets.mapNotNull { it.group }.distinct().map { ExportedGroup(it) }
                val json = ExportFormat.encode(WatchWalletExport(exportedAt = Clock.System.now().toString(), groups = usedGroups, wallets = wallets))
                val content = if (state.password.isNotEmpty() && ExportCrypto.isSupported) ExportCrypto.encrypt(json, state.password) else json
                content to "watchwallet-$stamp.json"
            }

            ExportFormatOption.CSV -> {
                val values = if (state.includeValues) walletValues() else emptyMap()
                val currency = settingsRepository.settings.first().quoteCurrency.code
                ExportFormat.toCsv(wallets, values, if (state.includeValues) "value_$currency".lowercase() else null) to "watchwallet-$stamp.csv"
            }

            ExportFormatOption.TEXT -> {
                ExportFormat.toText(wallets) to "watchwallet-addresses-$stamp.txt"
            }
        }
    }

    private suspend fun walletValues(): Map<String, String> {
        val currency = settingsRepository.settings.first().quoteCurrency
        val values = portfolioRepository.walletValues(currency).first()
        return walletRepository.activeWallets.first().mapNotNull { wallet ->
            val target = wallet.target ?: return@mapNotNull null
            val key = when (target) {
                is WatchTarget.Address -> target.address
                is WatchTarget.ExtendedPublicKey -> target.key
            }
            values[wallet.id]?.let { key to ((it * 100).toLong() / 100.0).toString() }
        }.toMap()
    }
}
