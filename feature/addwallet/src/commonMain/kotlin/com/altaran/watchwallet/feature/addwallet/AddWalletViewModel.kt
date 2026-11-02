package com.altaran.watchwallet.feature.addwallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.classifier.Classification
import com.altaran.watchwallet.core.data.AddWalletService
import com.altaran.watchwallet.core.data.CandidatePreview
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.security.SecretKind
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

    /** `0x` + 64 hex: the user must pick the chain explicitly (it may also be a private key). */
    data class ConfirmChain(val value: String, val options: List<ChainFamily>) : AddWalletPhase

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
)

internal class AddWalletViewModel(
    private val service: AddWalletService,
) : ViewModel() {
    private val _state = MutableStateFlow(AddWalletUiState())
    val state: StateFlow<AddWalletUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { service.groups.collect { groups -> _state.update { it.copy(groups = groups.map { g -> g.name }) } } }
    }

    fun onGroupChange(value: String) {
        _state.update { it.copy(groupName = value) }
    }
    private var probeJob: Job? = null

    fun chainName(id: com.altaran.watchwallet.core.model.ChainId): String = service.chainName(id)

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value, phase = AddWalletPhase.Idle) }
    }

    fun onNameChange(value: String) {
        _state.update { it.copy(name = value) }
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
        when (val result = service.classify(text)) {
            is Classification.Secret -> {
                // Discard immediately: the secret is not kept in the field, state or anywhere else.
                _state.update { AddWalletUiState(phase = AddWalletPhase.SecretRejected(result.kind)) }
            }

            is Classification.Unsupported -> {
                setPhase(text, AddWalletPhase.Unsupported(result.family))
            }

            is Classification.ConfirmationRequired -> {
                val options = result.options.filter(service::isSupported)
                setPhase(
                    text,
                    if (options.isEmpty()) AddWalletPhase.Unsupported(result.options.first()) else AddWalletPhase.ConfirmChain(result.value, options),
                )
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
    fun onConfirmChain(family: ChainFamily) {
        val phase = _state.value.phase as? AddWalletPhase.ConfirmChain ?: return
        val target = service.confirmedTarget(phase.value, family) ?: run {
            _state.update { it.copy(phase = AddWalletPhase.Unrecognized) }
            return
        }
        _state.update { it.copy(phase = AddWalletPhase.Probing) }
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
        _state.value = AddWalletUiState()
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
            val id = service.save(preview.candidate.target, current.name.ifBlank { defaultName }, preview.probe, current.groupName, subtractFrom)
            _state.update { it.copy(saving = false, savedWalletId = id) }
        }
    }

    private fun setPhase(text: String, phase: AddWalletPhase) {
        _state.update { it.copy(input = text, phase = phase) }
    }
}
