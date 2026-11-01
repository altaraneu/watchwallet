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
}

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
)

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
