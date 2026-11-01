package com.altaran.watchwallet.core.data

import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.core.classifier.AddressClassifier
import com.altaran.watchwallet.core.classifier.Candidate
import com.altaran.watchwallet.core.classifier.Classification
import com.altaran.watchwallet.core.data.chain.ChainRegistry
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.WatchTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Result of probing one candidate interpretation of the user's input. */
data class CandidatePreview(
    val candidate: Candidate,
    val probe: ProbeResult?,
    /** Set when the data source could not be reached; the wallet can still be added. */
    val failed: Boolean,
    /** Id of the wallet that already tracks this target, if any. */
    val existingWalletId: Long?,
)

/**
 * The "paste and done" flow: classify offline, probe on-chain activity in parallel, save.
 *
 * Secrets never get past [classify]: the classifier runs the secret guard first, and the input
 * is not stored or sent anywhere when it reports a secret.
 */
class AddWalletService(
    private val registry: ChainRegistry,
    private val walletRepository: WalletRepository,
    private val syncEngine: SyncEngine,
) {
    fun classify(input: String): Classification = when (val result = AddressClassifier.classify(input)) {
        is Classification.Candidates -> {
            val supported = result.candidates.filter { it.target.family in registry.supportedFamilies }
            if (supported.isEmpty()) Classification.Unsupported(result.candidates.first().target.family) else Classification.Candidates(supported)
        }
        else -> result
    }

    fun isSupported(family: ChainFamily): Boolean = family in registry.supportedFamilies

    fun chainName(id: com.altaran.watchwallet.core.model.ChainId): String = registry.chain(id)?.name ?: id.value

    suspend fun probe(candidates: List<Candidate>): List<CandidatePreview> = coroutineScope {
        candidates.map { candidate ->
            async {
                val existing = walletRepository.findExisting(candidate.target)
                val adapter = registry.adapter(candidate.target.family)
                val probe = adapter?.let { runCatching { it.probe(candidate.target) }.getOrNull() }
                CandidatePreview(candidate, probe, failed = probe == null, existingWalletId = existing)
            }
        }.awaitAll()
    }

    /** Saves the wallet and starts its first sync in the background. */
    suspend fun save(target: WatchTarget, name: String, probe: ProbeResult?): Long {
        val id = walletRepository.addWallet(target, name, probe?.activeChains.orEmpty())
        syncEngine.syncWallet(id)
        return id
    }
}
