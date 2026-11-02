package com.altaran.watchwallet.feature.addwallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.classifier.Classification
import com.altaran.watchwallet.core.data.AddWalletOperations
import com.altaran.watchwallet.core.data.CandidatePreview
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.security.SecretCheck
import com.altaran.watchwallet.core.security.SecretGuard
import com.altaran.watchwallet.core.security.SecretKind
import com.altaran.watchwallet.core.security.SecretRejectedException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AddWalletPhase {
    data object Idle : AddWalletPhase

    data object Probing : AddWalletPhase

    /** Key material was detected; the input field has already been cleared. */
    data class SecretRejected(val kind: SecretKind) : AddWalletPhase

    data class Unsupported(val family: ChainFamily) : AddWalletPhase

    data object WrongChecksum : AddWalletPhase

    data object MultipleEntries : AddWalletPhase

    /** The separate route for Sui and Aptos: first the chain… */
    data class DeliberateChain(val options: List<ChainFamily>) : AddWalletPhase

    /** …then the warning that the format is identical to a private key and goes to a public server… */
    data class DeliberateWarning(val family: ChainFamily) : AddWalletPhase

    /** …and only then a `0x` + 64 hex value is accepted in the input field. */
    data class DeliberateEntry(val family: ChainFamily, val invalid: Boolean = false) : AddWalletPhase

    /** The chosen address has never been used: second, stronger warning before saving. */
    data class ConfirmUnused(val preview: CandidatePreview) : AddWalletPhase

    /** The address already belongs to an extended key wallet: subtract it there, or count both? */
    data class ConfirmOverlap(val preview: CandidatePreview, val defaultName: String) : AddWalletPhase

    data object Unrecognized : AddWalletPhase

    /** Valid input; the user confirms one preview (pre-selected: the first with activity). */
    data class Preview(val previews: List<CandidatePreview>, val selected: Int) : AddWalletPhase
}

data class AddWalletUiState(
    val input: String = "",
    val name: String = "",
    val phase: AddWalletPhase = AddWalletPhase.Idle,
    val saving: Boolean = false,
    /** Set once the wallet is saved; the screen navigates away. */
    val savedWalletId: Long? = null,
    /** The preview should be saved as soon as it appears (one-tap add). */
    val autoSave: Boolean = false,
    /** Group (person or purpose) for the new wallet; empty = none. */
    val groupName: String = "",
    val groups: List<String> = emptyList(),
    /** A name or group was refused as key material and cleared; the rest of the screen stays. */
    val fieldRejected: Boolean = false,
)

internal class AddWalletViewModel(
    private val service: AddWalletOperations,
) : ViewModel() {
    private val _state = MutableStateFlow(AddWalletUiState())
    val state: StateFlow<AddWalletUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { service.groups.collect { groups -> _state.update { it.copy(groups = groups.map { g -> g.name }) } } }
    }

    /** Returns `true` when the value was refused, so the screen can clear the clipboard as well. */
    fun onGroupChange(value: String): Boolean {
        val secret = SecretGuard.isSecret(value)
        _state.update { it.copy(groupName = if (secret) "" else value, fieldRejected = secret) }
        return secret
    }
    private var probeJob: Job? = null

    fun chainName(id: com.altaran.watchwallet.core.model.ChainId): String = service.chainName(id)

    /**
     * Runs on every change of the field, however the text got there. Returns `true` when it was
     * rejected: the value is then gone from the state and the screen clears the clipboard too.
     */
    fun onInputChange(value: String): Boolean {
        val deliberate = _state.value.phase as? AddWalletPhase.DeliberateEntry
        val check = if (deliberate != null) SecretGuard.checkDeliberateAddress(value) else SecretGuard.check(value)
        if (check is SecretCheck.Secret) {
            reject(check.kind)
            return true
        }
        _state.update { it.copy(input = value, phase = deliberate?.copy(invalid = false) ?: AddWalletPhase.Idle) }
        return false
    }

    /** Returns `true` when the value was refused, so the screen can clear the clipboard as well. */
    fun onNameChange(value: String): Boolean {
        val secret = SecretGuard.isSecret(value)
        _state.update { it.copy(name = if (secret) "" else value, fieldRejected = secret) }
        return secret
    }

    /** Discards everything the user entered. The rejected value stays in no state and no message. */
    private fun reject(kind: SecretKind) {
        probeJob?.cancel()
        _state.update { AddWalletUiState(phase = AddWalletPhase.SecretRejected(kind), groups = it.groups) }
    }

    /** A scanned QR code. Raw bytes of a seed's length are a compact SeedQR. */
    fun onScanned(text: String, bytes: ByteArray?) {
        val binary = bytes?.let(SecretGuard::checkBinary)
        if (binary is SecretCheck.Secret) reject(binary.kind) else onSubmit(text.trim())
    }

    /** The deliberate route for Sui and Aptos addresses. */
    fun onStartDeliberate() {
        probeJob?.cancel()
        val options = listOf(ChainFamily.SUI, ChainFamily.APTOS).filter(service::isSupported)
        _state.update { it.copy(input = "", phase = AddWalletPhase.DeliberateChain(options), autoSave = false) }
    }

    fun onPickDeliberateChain(family: ChainFamily) {
        if (_state.value.phase !is AddWalletPhase.DeliberateChain) return
        _state.update { it.copy(phase = AddWalletPhase.DeliberateWarning(family)) }
    }

    fun onAcceptDeliberateWarning() {
        val phase = _state.value.phase as? AddWalletPhase.DeliberateWarning ?: return
        _state.update { it.copy(phase = AddWalletPhase.DeliberateEntry(phase.family)) }
    }

    /**
     * Submits text pasted from the clipboard. Returns `true` when it was rejected as key
     * material, so the screen can clear the clipboard.
     */
    fun onPaste(text: String): Boolean {
        onSubmit(text)
        return _state.value.phase is AddWalletPhase.SecretRejected
    }

    /**
     * Classifies the input. With [saveWhenUnambiguous] (the "Add wallet" button) a single
     * candidate is saved right after the check; otherwise the preview asks.
     */
    fun onSubmit(text: String = _state.value.input, saveWhenUnambiguous: Boolean = false) {
        probeJob?.cancel()
        _state.update { it.copy(autoSave = false) }
        (_state.value.phase as? AddWalletPhase.DeliberateEntry)?.let { entry ->
            submitDeliberate(text, entry.family)
            return
        }
        when (val result = service.classify(text)) {
            is Classification.Secret -> {
                // Discard immediately: the secret is not kept in the field, state or anywhere else.
                reject(result.kind)
            }

            is Classification.Unsupported -> {
                setPhase(text, AddWalletPhase.Unsupported(result.family))
            }

            Classification.WrongChecksum -> {
                setPhase(text, AddWalletPhase.WrongChecksum)
            }

            is Classification.Multiple -> {
                setPhase(text, AddWalletPhase.MultipleEntries)
            }

            Classification.Unrecognized -> {
                setPhase(text, AddWalletPhase.Unrecognized)
            }

            is Classification.Candidates -> {
                setPhase(text, AddWalletPhase.Probing)
                probeJob = viewModelScope.launch {
                    val previews = service.probe(result.candidates)
                    // Prefer candidates with activity; among them keep the classifier's order.
                    val selected = previews.indexOfFirst { it.probe?.hasActivity == true }.takeIf { it >= 0 } ?: 0
                    _state.update {
                        it.copy(phase = AddWalletPhase.Preview(previews, selected), autoSave = saveWhenUnambiguous && previews.size == 1)
                    }
                }
            }
        }
    }

    /** Probes only the chain the user picked; never any other. */
    private fun submitDeliberate(text: String, family: ChainFamily) {
        val check = SecretGuard.checkDeliberateAddress(text)
        if (check is SecretCheck.Secret) {
            reject(check.kind)
            return
        }
        val target = service.confirmedTarget(text.trim(), family) ?: run {
            _state.update { it.copy(input = text, phase = AddWalletPhase.DeliberateEntry(family, invalid = true)) }
            return
        }
        _state.update { it.copy(input = text, phase = AddWalletPhase.Probing) }
        probeJob = viewModelScope.launch {
            val preview = service.probe(
                listOf(com.altaran.watchwallet.core.classifier.Candidate(target, com.altaran.watchwallet.core.classifier.Confidence.CERTAIN)),
            ).single()
            val next = if (preview.probe?.hasActivity == true) AddWalletPhase.Preview(listOf(preview), 0) else AddWalletPhase.ConfirmUnused(preview)
            _state.update { it.copy(phase = next) }
        }
    }

    fun onConfirmUnused() {
        val phase = _state.value.phase as? AddWalletPhase.ConfirmUnused ?: return
        _state.update { it.copy(phase = AddWalletPhase.Preview(listOf(phase.preview), 0)) }
    }

    /** Discards the input (for example after the user realised it was a private key). */
    fun onClear() {
        probeJob?.cancel()
        _state.update { AddWalletUiState(groups = it.groups) }
    }

    fun onSelectCandidate(index: Int) {
        _state.update { state ->
            val phase = state.phase as? AddWalletPhase.Preview ?: return@update state
            state.copy(phase = phase.copy(selected = index))
        }
    }

    fun onSave(defaultName: String) {
        val current = _state.value
        val phase = current.phase as? AddWalletPhase.Preview ?: return
        val preview = phase.previews.getOrNull(phase.selected) ?: return
        preview.existingWalletId?.let { existing ->
            _state.update { it.copy(savedWalletId = existing) }
            return
        }
        if (preview.containingWallet != null) {
            _state.update { it.copy(phase = AddWalletPhase.ConfirmOverlap(preview, defaultName), autoSave = false) }
            return
        }
        persist(preview, defaultName, subtractFrom = null)
    }

    /** The user's answer to [AddWalletPhase.ConfirmOverlap]; the wallet is added either way. */
    fun onResolveOverlap(subtract: Boolean) {
        val phase = _state.value.phase as? AddWalletPhase.ConfirmOverlap ?: return
        persist(phase.preview, phase.defaultName, subtractFrom = phase.preview.containingWallet?.id.takeIf { subtract })
    }

    private fun persist(preview: CandidatePreview, defaultName: String, subtractFrom: Long?) {
        val current = _state.value
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val id = service.save(preview.candidate.target, current.name.ifBlank { defaultName }, preview.probe, current.groupName, subtractFrom)
                _state.update { it.copy(saving = false, savedWalletId = id) }
            } catch (e: SecretRejectedException) {
                // The last line of defence: a warning, never a crash.
                reject(e.kind)
            }
        }
    }

    private fun setPhase(text: String, phase: AddWalletPhase) {
        _state.update { it.copy(input = text, phase = phase) }
    }
}
