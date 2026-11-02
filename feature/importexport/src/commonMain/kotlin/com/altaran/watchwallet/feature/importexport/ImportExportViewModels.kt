package com.altaran.watchwallet.feature.importexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.data.repository.CostBasisRepository
import com.altaran.watchwallet.core.data.repository.CostKey
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.importexport.BatchEntry
import com.altaran.watchwallet.core.importexport.BatchParser
import com.altaran.watchwallet.core.importexport.BatchResult
import com.altaran.watchwallet.core.importexport.EntryStatus
import com.altaran.watchwallet.core.importexport.ExportFormat
import com.altaran.watchwallet.core.importexport.ExportedCost
import com.altaran.watchwallet.core.importexport.ExportedGroup
import com.altaran.watchwallet.core.importexport.ExportedWallet
import com.altaran.watchwallet.core.importexport.TransactionRow
import com.altaran.watchwallet.core.importexport.WatchWalletExport
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.WalletGroup
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.security.SecretKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** Key derivation and decryption run off the main thread. */
    data object Decrypting : ImportPhase
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
    pendingImport: PendingImport,
    private val costBasis: CostBasisRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()
    private var encrypted: String? = null

    init {
        // Prefilled text (for example from the add-wallet screen) is consumed exactly once, so a
        // configuration change does not parse it again and wipe the preview selection.
        pendingImport.consume()?.takeIf { it.isNotBlank() }?.let(::parse)
    }

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
        if (_state.value.phase == ImportPhase.Decrypting) return
        _state.update { it.copy(phase = ImportPhase.Decrypting) }
        viewModelScope.launch {
            // PBKDF2 with 600 000 iterations takes seconds on slower phones.
            val plain = withContext(Dispatchers.Default) { ExportCrypto.decrypt(envelope, password) }
            if (plain == null) {
                _state.update { it.copy(phase = ImportPhase.WrongPassword) }
            } else {
                encrypted = null
                parse(plain)
            }
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
            val addedLinks = mutableMapOf<Long, String>()
            phase.rows.forEach { row ->
                val target = row.entry.candidates.getOrNull(row.chosen)
                when {
                    !row.selected -> skipped++

                    target == null -> failed++

                    walletRepository.findExisting(target) != null -> skipped++

                    else -> runCatching {
                        val walletId = walletRepository.addWallet(
                            target,
                            row.entry.label ?: defaultName(target),
                            emptyList(),
                            groupId(row.entry.group ?: commonGroup),
                            row.entry.notes,
                        )
                        row.entry.subtractFrom?.let { addedLinks[walletId] = it }
                        costBasis.restoreUserValues(
                            row.entry.costs.mapNotNull { cost ->
                                cost.decoded()?.let { (hash, assetId) -> CostKey(walletId, hash, assetId) to cost.valueUsd }
                            }.toMap(),
                        )
                    }.onSuccess { imported++ }.onFailure { failed++ }
                }
            }
            // Links last: the wallet they point to can come later in the file.
            val all = walletRepository.activeWallets.first()
            addedLinks.forEach { (walletId, parentValue) ->
                val parent = all.firstOrNull { wallet -> wallet.target?.let { ExportedWallet.from(it, "", null, null).value } == parentValue }
                if (parent != null) walletRepository.setSubtractFrom(walletId, parent.id)
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

enum class ExportFormatOption { JSON, CSV, TEXT, TRANSACTIONS }

data class ExportUiState(
    val groups: List<WalletGroup> = emptyList(),
    /** `null` = all wallets. */
    val groupId: Long? = null,
    val format: ExportFormatOption = ExportFormatOption.JSON,
    val includeValues: Boolean = false,
    val password: String = "",
    val passwordConfirmation: String = "",
    val walletCount: Int = 0,
    val message: ExportMessage? = null,
    /** The export is being built (and encrypted) in the background. */
    val busy: Boolean = false,
    /** A finished export waiting for the screen to save or share it. */
    val prepared: PreparedExport? = null,
) {
    /** Why the password keeps the export from starting; only the encrypted format has one. */
    val passwordProblem: PasswordProblem?
        get() = if (format == ExportFormatOption.JSON && ExportCrypto.isSupported) passwordProblem(password, passwordConfirmation) else null
}

enum class ExportMessage { SAVED, SAVE_FAILED }

enum class PasswordProblem { TOO_SHORT, MISMATCH }

/** Long enough to resist offline guessing of a stolen backup; the key derivation only slows that down. */
const val MIN_EXPORT_PASSWORD_LENGTH = 12

/** A lost password cannot be recovered, so a typo must not reach the file. Empty = no encryption. */
fun passwordProblem(password: String, confirmation: String): PasswordProblem? = when {
    password.isEmpty() -> if (confirmation.isEmpty()) null else PasswordProblem.MISMATCH
    password.length < MIN_EXPORT_PASSWORD_LENGTH -> PasswordProblem.TOO_SHORT
    password != confirmation -> PasswordProblem.MISMATCH
    else -> null
}

/** A built export; [share] = share sheet, otherwise save to a file. */
data class PreparedExport(val content: String, val fileName: String, val mimeType: String, val share: Boolean)

/**
 * @param walletValuesIn current value per wallet id, for the CSV export with values.
 * @param userCosts the cost basis the user entered, for the JSON backup.
 */
internal class ExportViewModel(
    private val walletRepository: WalletRepository,
    private val walletValuesIn: (QuoteCurrency) -> Flow<Map<Long, Double>>,
    private val settingsRepository: SettingsRepository,
    private val userCosts: suspend () -> Map<CostKey, Double>,
) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    /**
     * The file being saved while the system file picker is open. Lives here and not in the screen,
     * which is recreated when the device rotates behind the picker.
     */
    private var pendingSave: String? = null

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
    fun setPasswordConfirmation(value: String) = _state.update { it.copy(passwordConfirmation = value) }
    fun showMessage(message: ExportMessage?) = _state.update { it.copy(message = message) }

    /**
     * Builds the export in the background; the screen then saves or shares [ExportUiState.prepared]
     * and calls [onPreparedHandled]. The password is cleared from the state afterwards.
     */
    fun prepare(share: Boolean) {
        if (_state.value.busy || _state.value.passwordProblem != null) return
        val format = _state.value.format
        _state.update { it.copy(busy = true, message = null, prepared = null) }
        viewModelScope.launch {
            val built = runCatching { build() }.getOrNull()
            pendingSave = built?.first?.takeUnless { share }
            _state.update { state ->
                state.copy(
                    busy = false,
                    password = "",
                    passwordConfirmation = "",
                    prepared = built?.let { (content, name) -> PreparedExport(content, name, mimeType(format), share) },
                    message = if (built == null) ExportMessage.SAVE_FAILED else state.message,
                )
            }
        }
    }

    fun onPreparedHandled() = _state.update { it.copy(prepared = null) }

    /** Hands the file content to the platform once, when the user has chosen where to save it. */
    fun takePendingSave(): String? = pendingSave.also { pendingSave = null }

    /** A cancelled picker is not a failure, and whatever was shown before no longer applies. */
    fun onSaveFinished(outcome: SaveOutcome) = showMessage(
        when (outcome) {
            SaveOutcome.SAVED -> ExportMessage.SAVED
            SaveOutcome.FAILED -> ExportMessage.SAVE_FAILED
            SaveOutcome.CANCELLED -> null
        },
    )

    private fun mimeType(format: ExportFormatOption): String = when (format) {
        ExportFormatOption.JSON -> "application/json"
        ExportFormatOption.CSV, ExportFormatOption.TRANSACTIONS -> "text/csv"
        ExportFormatOption.TEXT -> "text/plain"
    }

    /** Builds the export file content and a suggested file name. */
    private suspend fun build(): Pair<String, String> {
        val state = _state.value
        val groups = walletRepository.groups.first()
        val groupNames = groups.associate { it.id to it.name }
        val costs = userCosts()
        val allWallets = walletRepository.activeWallets.first()
        val wallets = allWallets
            .filter { state.groupId == null || it.groupId == state.groupId }
            .mapNotNull { wallet ->
                wallet.target?.let { target ->
                    ExportedWallet.from(target, wallet.name, wallet.groupId?.let(groupNames::get), wallet.notes).copy(
                        costs = costs.filterKeys { it.walletId == wallet.id }.map { (key, value) -> ExportedCost.of(key.hash, key.assetId, value) },
                        subtractFrom = allWallets.firstOrNull { it.id == wallet.subtractFromWalletId }?.target?.let {
                            ExportedWallet.from(it, "", null, null).value
                        },
                    )
                }
            }
        val stamp = Clock.System.now().toString().take(10)
        return when (state.format) {
            ExportFormatOption.JSON -> {
                val usedGroups = wallets.mapNotNull { it.group }.distinct().map { ExportedGroup(it) }
                val json = ExportFormat.encode(WatchWalletExport(exportedAt = Clock.System.now().toString(), groups = usedGroups, wallets = wallets))
                val content = if (state.password.isNotEmpty() && ExportCrypto.isSupported) {
                    // PBKDF2 key derivation is deliberately slow: keep it off the main thread.
                    withContext(Dispatchers.Default) { ExportCrypto.encrypt(json, state.password) }
                } else {
                    json
                }
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

            ExportFormatOption.TRANSACTIONS -> {
                ExportFormat.transactionsCsv(transactionRows(state.groupId, groupNames)) to "watchwallet-transactions-$stamp.csv"
            }
        }
    }

    /** Stored transactions of the exported wallets, formatted for the history CSV. */
    private suspend fun transactionRows(groupId: Long?, groupNames: Map<Long, String>): List<TransactionRow> {
        val wallets = walletRepository.activeWallets.first().filter { groupId == null || it.groupId == groupId }.associateBy { it.id }
        val assets = walletRepository.assets.first()
        return walletRepository.allTransactions.first().mapNotNull { tx ->
            val wallet = wallets[tx.walletId] ?: return@mapNotNull null
            val asset = assets[tx.assetId] ?: return@mapNotNull null
            val native = assets[AssetId.native(tx.chainId)]
            TransactionRow(
                dateUtc = tx.timestamp.toString(),
                wallet = wallet.name,
                group = wallet.groupId?.let(groupNames::get),
                chain = tx.chainId.value,
                asset = asset.symbol,
                amount = TokenAmount(tx.delta, asset.decimals).toPlainString(),
                fee = tx.fee?.let { fee -> native?.let { TokenAmount(fee, it.decimals).toPlainString() } },
                feeAsset = native?.symbol?.takeIf { tx.fee != null },
                direction = tx.direction.name.lowercase(),
                confirmed = tx.confirmed,
                hash = tx.hash,
            )
        }
    }

    private suspend fun walletValues(): Map<String, String> {
        val currency = settingsRepository.settings.first().quoteCurrency
        val values = walletValuesIn(currency).first()
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
