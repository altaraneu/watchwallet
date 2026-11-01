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
)

internal class AddWalletViewModel(
    private val service: AddWalletService,
) : ViewModel() {
    private val _state = MutableStateFlow(AddWalletUiState())
    val state: StateFlow<AddWalletUiState> = _state.asStateFlow()
    private var probeJob: Job? = null

    fun chainName(id: com.altaran.watchwallet.core.model.ChainId): String = service.chainName(id)

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value, phase = AddWalletPhase.Idle) }
    }

    fun onNameChange(value: String) {
        _state.update { it.copy(name = value) }
    }

    /** Classifies the current input. Called on paste and on the "Continue" action. */
    fun onSubmit(text: String = _state.value.input) {
        probeJob?.cancel()
        when (val result = service.classify(text)) {
            is Classification.Secret -> {
                // Discard immediately: the secret is not kept in the field, state or anywhere else.
                _state.update { AddWalletUiState(phase = AddWalletPhase.SecretRejected(result.kind)) }
            }
            is Classification.Unsupported -> setPhase(text, AddWalletPhase.Unsupported(result.family))
            is Classification.ConfirmationRequired -> setPhase(text, AddWalletPhase.Unsupported(result.options.first()))
            Classification.WrongChecksum -> setPhase(text, AddWalletPhase.WrongChecksum)
            is Classification.Multiple -> setPhase(text, AddWalletPhase.MultipleEntries)
            Classification.Unrecognized -> setPhase(text, AddWalletPhase.Unrecognized)
            is Classification.Candidates -> {
                setPhase(text, AddWalletPhase.Probing)
                probeJob = viewModelScope.launch {
                    val previews = service.probe(result.candidates)
                    // Prefer candidates with activity; among them keep the classifier's order.
                    val selected = previews.indexOfFirst { it.probe?.hasActivity == true }.takeIf { it >= 0 } ?: 0
                    _state.update { it.copy(phase = AddWalletPhase.Preview(previews, selected)) }
                }
            }
        }
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
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val id = service.save(preview.candidate.target, current.name.ifBlank { defaultName }, preview.probe)
            _state.update { it.copy(saving = false, savedWalletId = id) }
        }
    }

    private fun setPhase(text: String, phase: AddWalletPhase) {
        _state.update { it.copy(input = text, phase = phase) }
    }
}
