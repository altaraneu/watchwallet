package com.altaran.watchwallet.chain.api

import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.DerivedAddress
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.WatchTarget
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.time.Instant

/**
 * One blockchain family. Adapters live in `:chain:*` modules and are registered in Koin; the UI
 * never talks to them directly, only through repositories.
 *
 * Adapters must only send individual addresses (or script hashes) over the network — never an
 * extended public key.
 */
interface ChainAdapter {
    val family: ChainFamily

    /** Chains handled by this adapter (one for Bitcoin, many for EVM). */
    val chains: List<Chain>

    /** Quick check whether the target has any balance or history, used before saving a wallet. */
    suspend fun probe(target: WatchTarget): ProbeResult

    /**
     * Fetches balances and new transactions.
     *
     * @param knownTransactions hashes already stored for this wallet; adapters stop paging when they
     * reach them (incremental sync).
     */
    suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult

    /**
     * Like [sync]; with [continueHistory] (an earlier sync reported `historyTruncated`) adapters
     * that page history fetch a further chunk of older transactions instead of stopping at the
     * known ones. The default ignores the flag.
     */
    suspend fun sync(target: WatchTarget, knownTransactions: Set<String>, continueHistory: Boolean): SyncResult =
        sync(target, knownTransactions)

    /**
     * `true` when [sync] with `continueHistory` really fetches older transactions, so a sync that
     * reports `historyTruncated = false` means the history is complete. Adapters that ignore the
     * flag keep a wallet marked as truncated once it was.
     */
    val supportsHistoryContinuation: Boolean get() = false

    /**
     * The entry point of the sync engine. Multi-part adapters override it, hand every finished part
     * to [sink] as soon as it is ready and return [SyncResult.storedProgressively]; the default
     * returns everything at once. [sink] may be called concurrently, and a part handed over is
     * stored even when the sync fails later.
     */
    suspend fun sync(target: WatchTarget, knownTransactions: Set<String>, options: SyncOptions, sink: SyncPartSink): SyncResult =
        sync(target, knownTransactions, options.continueHistory)

    /** Forgets remembered host failures: the user asked to try everything again. */
    suspend fun resetHostHealth() = Unit
}

/** What the engine knows about the wallet before a sync. */
data class SyncOptions(
    /** Chains with activity found by earlier syncs. */
    val activeChains: Set<ChainId> = emptySet(),
    /** When the adapter last searched all its chains for activity. */
    val lastDiscoveryAt: Instant? = null,
    /** The user asked for this refresh: look at everything and do not trust remembered failures. */
    val manual: Boolean = false,
    /** An earlier sync reported `historyTruncated`. */
    val continueHistory: Boolean = false,
    /** Adapter-defined state per chain, from [SyncPart.state]. */
    val chainState: Map<ChainId, String> = emptyMap(),
    /** Extended keys: derived addresses earlier syncs found with history. */
    val usedAddresses: Set<String> = emptySet(),
    /** Debug timing log; messages must never contain addresses or amounts. */
    val trace: (String) -> Unit = {},
)

/** Receives finished parts of a sync; each one is stored in its own transaction. */
fun interface SyncPartSink {
    suspend fun store(part: SyncPart)
}

/**
 * What is known so far about one chain of a wallet. Chains without a part keep all stored data, so
 * a chain that failed loses nothing.
 */
data class SyncPart(
    val chainId: ChainId,
    val balances: List<AssetAmount> = emptyList(),
    /** `true`: [balances] replaces all stored balances of the chain. `false`: only these assets change. */
    val balancesComplete: Boolean = false,
    val transactions: List<ChainTransaction> = emptyList(),
    /** History was fetched, so stored pending transactions of the chain are replaced by [transactions]. */
    val historyFetched: Boolean = false,
    /** New activity status of the chain; `null` leaves it unchanged. */
    val active: Boolean? = null,
    /** Stored after the part and returned in [SyncOptions.chainState] next time; `null` keeps the old one. */
    val state: String? = null,
)

data class ProbeResult(
    val hasActivity: Boolean,
    /** Chains where the target has balance or history (EVM: networks; others: the single chain). */
    val activeChains: List<ChainId>,
    val balances: List<AssetAmount>,
    val transactionCount: Int?,
    /** Extended keys: number of derived addresses with history. */
    val usedAddressCount: Int? = null,
)

data class AssetAmount(val asset: Asset, val amount: TokenAmount)

data class SyncResult(
    val balances: List<AssetAmount>,
    /** New or changed transactions (pending ones may later confirm). */
    val transactions: List<ChainTransaction>,
    val derivedAddresses: List<DerivedAddress> = emptyList(),
    val activeChains: List<ChainId> = emptyList(),
    /** `true` when history was cut off (for example an exchange wallet with huge history). */
    val historyTruncated: Boolean = false,
    /**
     * Chains whose balances [balances] fully describes. `null` (the default) = the result is a
     * complete snapshot and replaces every stored balance of the wallet. Multi-chain adapters list
     * only the chains they could fetch, so stored balances of unreachable chains are kept.
     */
    val syncedChains: List<ChainId>? = null,
    /** Everything was already handed to the [SyncPartSink]; only [historyTruncated] and below count. */
    val storedProgressively: Boolean = false,
    /** A search of all chains finished, so the next one can wait. */
    val discoveryCompleted: Boolean = false,
    /** Chains that could not be fully refreshed; they keep their last known data. */
    val failedChains: List<ChainId> = emptyList(),
    /** Every chain with activity failed, which the UI reports as a failed sync. */
    val allChainsFailed: Boolean = false,
    /** Those of [failedChains] whose native balance is current anyway; only tokens or history failed. */
    val nativeCurrentChains: List<ChainId> = emptyList(),
    /** `false`: a limit was hit, so assets missing from [balances] may still be held and stay stored. */
    val balancesComplete: Boolean = true,
    /** Stored transactions whose amounts changed, because an address found later took part in them. */
    val correctedTransactions: List<ChainTransaction> = emptyList(),
) {
    /** The token list of [chainId] hit a limit: known tokens stay stored and the wallet shows a hint. */
    fun withIncompleteBalances(chainId: ChainId): SyncResult = copy(
        balancesComplete = false,
        failedChains = listOf(chainId),
        allChainsFailed = true,
        nativeCurrentChains = listOf(chainId),
    )
}

/** A transaction's net effect on the wallet for one asset. */
data class ChainTransaction(
    val chainId: ChainId,
    val hash: String,
    val timestamp: Instant,
    val asset: Asset,
    /** Net change of the wallet balance, fee included for the fee-paying asset. */
    val delta: BigInteger,
    val fee: BigInteger?,
    /** `true` when every output went back to the wallet itself. */
    val isSelfTransfer: Boolean,
    val confirmed: Boolean,
) {
    val assetId: AssetId get() = asset.id
}

/** Thrown when a data source is unreachable or answers with an unexpected payload. */
class ChainDataException(message: String, cause: Throwable? = null) : Exception(message, cause)
