package com.altaran.watchwallet.core.data

import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.cosmos.CosmosAddresses
import com.altaran.watchwallet.core.classifier.AddressClassifier
import com.altaran.watchwallet.core.classifier.Candidate
import com.altaran.watchwallet.core.classifier.Classification
import com.altaran.watchwallet.core.classifier.Confidence
import com.altaran.watchwallet.core.data.chain.ChainParsers
import com.altaran.watchwallet.core.data.chain.ChainRegistry
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.WatchTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

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
            val supported = result.candidates.filter { registry.supports(it.target) }
            if (supported.isEmpty()) Classification.Unsupported(result.candidates.first().target.family) else Classification.Candidates(supported)
        }

        // Formats of the other chains (Litecoin, Solana, XRP, Cardano…), validated with checksums.
        is Classification.Unsupported, Classification.Unrecognized -> {
            val targets = ChainParsers.parse(input).filter { registry.supports(it) }
            if (targets.isEmpty()) {
                result
            } else {
                Classification.Candidates(targets.map { Candidate(it, if (targets.size == 1) Confidence.CERTAIN else Confidence.POSSIBLE) })
            }
        }

        else -> {
            result
        }
    }

    /** Interprets a `0x` + 64 hex value after the user explicitly picked Sui or Aptos. */
    fun confirmedTarget(value: String, family: ChainFamily): WatchTarget? = ChainParsers.parseConfirmed(value, family)

    /** Non-Bitcoin/EVM parsing for batch import. */
    fun parseOtherChains(input: String): List<WatchTarget> = ChainParsers.parse(input).filter { registry.supports(it) }

    fun isSupported(family: ChainFamily): Boolean = family in registry.supportedFamilies

    fun chainName(id: com.altaran.watchwallet.core.model.ChainId): String = registry.chain(id)?.name ?: id.value

    suspend fun probe(candidates: List<Candidate>): List<CandidatePreview> = coroutineScope {
        candidates.map { candidate ->
            async {
                val existing = walletRepository.findExisting(candidate.target)
                val adapter = registry.adapter(candidate.target)
                val probe = adapter?.let { withTimeoutOrNull(PROBE_TIMEOUT) { runCatching { it.probe(candidate.target) }.getOrNull() } }
                CandidatePreview(candidate, probe, failed = probe == null, existingWalletId = existing)
            }
        }.awaitAll()
    }

    /**
     * Saves the wallet and starts its first sync in the background. For a Cosmos SDK account the
     * same key on the sister chains (same coin type, different prefix) is checked as well, and
     * the ones with activity are added as separate wallets named "<name> (<chain>)".
     */
    suspend fun save(target: WatchTarget, name: String, probe: ProbeResult?, groupName: String? = null): Long {
        // A group is a person or purpose ("Partner", "Savings"); created on first use.
        val groupId = groupName?.trim()?.takeIf { it.isNotEmpty() }?.let { walletRepository.findGroup(it) ?: walletRepository.createGroup(it) }
        val id = walletRepository.addWallet(target, name, probe?.activeChains.orEmpty(), groupId)
        syncEngine.syncWallet(id)
        if (target is WatchTarget.Address && target.family == ChainFamily.COSMOS) addRelatedCosmos(target, name, groupId)
        return id
    }

    /** Existing groups, offered when adding a wallet. */
    val groups get() = walletRepository.groups

    private suspend fun addRelatedCosmos(target: WatchTarget.Address, name: String, groupId: Long?) = coroutineScope {
        CosmosAddresses.relatedAddresses(target.address).filter { registry.supports(it) }.map { related ->
            async {
                if (walletRepository.findExisting(related) != null) return@async
                val probe = registry.adapter(related)?.let { runCatching { it.probe(related) }.getOrNull() } ?: return@async
                if (!probe.hasActivity) return@async
                val id = walletRepository.addWallet(related, "$name (${chainName(related.chainId)})", probe.activeChains, groupId)
                syncEngine.syncWallet(id)
            }
        }.awaitAll()
    }
}

/** Longest wait for the activity check when adding a wallet; afterwards it can be added unchecked. */
private val PROBE_TIMEOUT = 10.seconds
