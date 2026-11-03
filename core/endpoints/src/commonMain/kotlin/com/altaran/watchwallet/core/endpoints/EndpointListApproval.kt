package com.altaran.watchwallet.core.endpoints

import kotlin.time.Clock

/** A verified list waiting for the user: it names servers the app does not use for that chain yet. */
data class EndpointApprovalRequest(val version: Long, val signedByRecoveryKey: Boolean, val added: List<String>)

/**
 * Consent before use. A verified list that adds a server waits here, without effect, until the user
 * approves it: the current servers stay in force, also across restarts. A list the user declined is
 * not offered again; only a higher version can ask again. Approving always refers to what the user
 * saw, so a newer list arriving behind the dialog is not approved by accident.
 */
class EndpointListApproval(
    private val store: EndpointConfigStore,
    private val verifier: EndpointFileVerifier = EndpointFileVerifier(),
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    /**
     * What to ask now, if anything. A list that expired while waiting no longer needs an answer, but
     * stays stored as declined, so its version still rejects older files. One whose additions meanwhile
     * came into use (an app update can do that) has nothing left to ask and applies.
     */
    suspend fun pending(): EndpointApprovalRequest? {
        if (!store.autoUpdateEnabled()) return null
        val envelope = store.pendingEnvelope() ?: return null
        val file = (verifier.open(envelope) as? EndpointFileResult.Valid)?.file
        val now = nowSeconds()
        if (file == null || !file.isUsable(now)) {
            store.declineEnvelope(envelope)
            return null
        }
        val added = hostsAddedBy(file, store.storedEnvelope(), now, verifier)
        if (added.isEmpty()) {
            store.applyEnvelope(envelope)
            return null
        }
        return EndpointApprovalRequest(file.version, file.signedByRecoveryKey, added)
    }

    /** Applies at the next app start, like every list. Does nothing if the waiting list changed. */
    suspend fun approve(request: EndpointApprovalRequest) {
        if (pending() == request) store.pendingEnvelope()?.let { store.applyEnvelope(it) }
    }

    suspend fun decline(request: EndpointApprovalRequest) {
        if (pending() == request) store.pendingEnvelope()?.let { store.declineEnvelope(it) }
    }
}

/**
 * The servers a list would add to what is in force: hosts, with port, that do not serve that chain
 * and API type yet. A host known from another chain counts, as it would learn other addresses. An
 * expired list no longer counts as in force, so its servers need approval again.
 */
internal fun hostsAddedBy(file: EndpointFile, appliedEnvelope: String?, nowSeconds: Long, verifier: EndpointFileVerifier): List<String> {
    val current = EndpointCatalog.resolve(appliedEnvelope, autoUpdate = true, customEsplora = null, nowSeconds = nowSeconds, verifier = verifier)
    return EndpointCatalog.of(file).addedComparedTo(current)
}
