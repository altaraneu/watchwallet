package com.altaran.watchwallet.core.data.sync

import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.SyncOptions
import com.altaran.watchwallet.chain.api.SyncPart
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.evm.SpamFilter
import com.altaran.watchwallet.core.data.chain.ChainRegistry
import com.altaran.watchwallet.core.data.platform.SyncLog
import com.altaran.watchwallet.core.data.repository.toEntity
import com.altaran.watchwallet.core.data.repository.toTarget
import com.altaran.watchwallet.core.database.dao.WalletDao
import com.altaran.watchwallet.core.database.entity.DerivedAddressEntity
import com.altaran.watchwallet.core.database.entity.WalletBalanceEntity
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.database.entity.WalletTxEntity
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.safeDescription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

data class SyncState(
    val running: Boolean = false,
    /** The running sync was requested by the user (pull to refresh); only then is a spinner shown. */
    val manual: Boolean = false,
    /** Wallet ids currently syncing. */
    val syncingWallets: Set<Long> = emptySet(),
    /** Wallet id → last error (cleared on success): type and HTTP status at most, never a URL or address. */
    val errors: Map<Long, String> = emptyMap(),
    /**
     * Wallet id → chains that could not be fully refreshed although the wallet as a whole could;
     * they show their last known data.
     */
    val partialFailures: Map<Long, Set<String>> = emptyMap(),
    /** Wallet ids whose native balances are current although nothing else could be refreshed. */
    val nativeOnly: Set<Long> = emptySet(),
) {
    fun healthOf(walletId: Long): WalletSyncHealth = when (walletId) {
        in errors -> WalletSyncHealth.FAILED
        in nativeOnly -> WalletSyncHealth.NATIVE_ONLY
        in partialFailures -> WalletSyncHealth.PARTIAL
        else -> WalletSyncHealth.OK
    }
}

/** How the last sync of a wallet ended, as far as the user should know. */
enum class WalletSyncHealth { OK, NATIVE_ONLY, PARTIAL, FAILED }

/** Refreshes current prices; the prices of a wallet just synced should not wait for the others. */
fun interface CurrentPriceRefresher {
    suspend fun refreshCurrentPrices()

    /** Forgets remembered host failures: the user asked to try everything again. */
    suspend fun resetHostHealth() = Unit
}

/**
 * Fetches balances and history for wallets and stores each result atomically: the whole result, or
 * part by part (one transaction per chain) for adapters that report progress. A bounded number of
 * wallets sync at a time so public APIs are not flooded.
 */
class SyncEngine(
    private val walletDao: WalletDao,
    private val registry: ChainRegistry,
    private val prices: CurrentPriceRefresher,
    private val syncStates: WalletSyncStateStore,
    private val log: SyncLog = SyncLog.None,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val mutex = Mutex()

    /** Wallet ids with a sync in flight (from [syncAll] or [syncWallet]); guarded by [inFlightLock]. */
    private val inFlight = HashSet<Long>()
    private val inFlightLock = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Starts a background sync of all wallets (no-op if one is running) and refreshes prices.
     * [manual] marks a user request, so the UI shows progress until the (possibly already
     * running) sync finishes.
     */
    fun syncAll(manual: Boolean = false): Job = scope.launch {
        if (manual && _state.value.running) _state.update { it.copy(manual = true) }
        // Atomic check-and-acquire: a second request while one runs is a no-op.
        if (!mutex.tryLock()) return@launch
        try {
            _state.update { it.copy(running = true, manual = manual || it.manual) }
            try {
                if (manual) {
                    registry.resetHostHealth()
                    prices.resetHostHealth()
                }
                val wallets = walletDao.activeWallets()
                // Errors of wallets that were deleted (or archived) are no longer relevant.
                val ids = wallets.map { it.id }.toSet()
                _state.update { state ->
                    state.copy(
                        errors = state.errors.filterKeys { it in ids },
                        partialFailures = state.partialFailures.filterKeys { it in ids },
                        nativeOnly = state.nativeOnly.filter { it in ids }.toSet(),
                    )
                }
                // Archived wallets keep theirs: only bookkeeping of deleted wallets goes away.
                syncStates.retain(walletDao.allWalletIds().toSet())
                coroutineScope {
                    // Cached prices are on screen already; fresh ones do not wait for the wallets.
                    launch { prices.refreshCurrentPrices() }
                    // Wallets sync in parallel (bounded) so one large wallet does not block the others;
                    // each result is stored as soon as it is ready.
                    val gate = Semaphore(PARALLEL_WALLETS)
                    wallets.forEach { wallet -> launch { gate.withPermit { syncOne(wallet, manual) } } }
                }
                // New assets may have appeared; prices fetched moments ago are not fetched again.
                prices.refreshCurrentPrices()
            } finally {
                // syncingWallets is maintained by syncOne itself (a single-wallet sync may still run).
                _state.update { it.copy(running = false, manual = false) }
            }
        } finally {
            mutex.unlock()
        }
    }

    fun syncWallet(walletId: Long): Job = scope.launch {
        val wallet = walletDao.wallet(walletId)
        if (wallet == null) {
            _state.update { it.copy(errors = it.errors - walletId) }
            return@launch
        }
        syncOne(wallet, manual = false)
        prices.refreshCurrentPrices()
    }

    private companion object {
        const val PARALLEL_WALLETS = 3
    }

    /** Chains that could not be synced this time keep their previous activity. */
    private fun activeChainsAfter(wallet: WalletEntity, result: SyncResult): List<String> {
        val fresh = result.activeChains.map { it.value }
        val synced = result.syncedChains?.map { it.value }?.toSet() ?: return fresh
        val previous = wallet.activeChains.split(',').filter { it.isNotBlank() }
        return (fresh + previous.filter { it !in synced }).distinct()
    }

    private fun ChainTransaction.toEntity(walletId: Long) = WalletTxEntity(
        walletId = walletId,
        chainId = chainId.value,
        hash = hash,
        assetId = asset.id.value,
        timestamp = timestamp.toEpochMilliseconds(),
        delta = delta.toString(),
        fee = fee?.toString(),
        isSelf = isSelfTransfer,
        confirmed = confirmed,
    )

    private suspend fun syncOne(snapshot: WalletEntity, manual: Boolean) {
        val target = snapshot.toTarget() ?: return
        val adapter = registry.adapter(target) ?: return
        // The same wallet never syncs twice at once (syncAll and syncWallet can overlap).
        if (!inFlightLock.withLock { inFlight.add(snapshot.id) }) return
        _state.update { it.copy(syncingWallets = it.syncingWallets + snapshot.id) }
        // `wallet` is re-read below; the finally block only needs the id.
        val walletId = snapshot.id
        val started = Clock.System.now()
        try {
            // Re-read: another sync may have finished since the snapshot (truncation, active chains).
            val wallet = walletDao.wallet(walletId)
            if (wallet == null) {
                _state.update { it.copy(errors = it.errors - walletId, partialFailures = it.partialFailures - walletId, nativeOnly = it.nativeOnly - walletId) }
                return
            }
            val result = runAdapter(wallet, target, adapter, manual)
            _state.update { state ->
                val failed = result.failedChains.map { it.value }.toSet()
                // The only networks failed, but their native balances are current: no failed sync.
                val nativeOnly = result.allChainsFailed && failed.isNotEmpty() && failed.all { id -> result.nativeCurrentChains.any { it.value == id } }
                val failedOutright = result.allChainsFailed && !nativeOnly
                state.copy(
                    errors = if (failedOutright) state.errors + (walletId to "All chains failed") else state.errors - walletId,
                    partialFailures = if (failed.isEmpty() || failedOutright) {
                        state.partialFailures - walletId
                    } else {
                        state.partialFailures + (walletId to failed)
                    },
                    nativeOnly = if (nativeOnly) state.nativeOnly + walletId else state.nativeOnly - walletId,
                )
            }
            log.log(
                "wallet=$walletId done ${(Clock.System.now() - started).inWholeMilliseconds}ms failed=${result.failedChains.joinToString(",") {
                    it.value
                }}",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.log("wallet=$walletId failed ${(Clock.System.now() - started).inWholeMilliseconds}ms ${e::class.simpleName}")
            // Never the message of a foreign exception: an HTTP client's holds the URL with the address.
            _state.update { it.copy(errors = it.errors + (walletId to e.safeDescription())) }
        } finally {
            _state.update { it.copy(syncingWallets = it.syncingWallets - walletId) }
            withContext(NonCancellable) { inFlightLock.withLock { inFlight.remove(walletId) } }
        }
    }

    private suspend fun runAdapter(wallet: WalletEntity, target: WatchTarget, adapter: ChainAdapter, manual: Boolean): SyncResult {
        var syncState = syncStates.load(wallet.id)
        val stateLock = Mutex()
        val options = SyncOptions(
            activeChains = wallet.activeChains.split(',').filter { it.isNotBlank() }.map(::ChainId).toSet(),
            lastDiscoveryAt = syncState.discoveredAt?.let(Instant::fromEpochMilliseconds),
            manual = manual,
            continueHistory = wallet.historyTruncated,
            chainState = syncState.chains.mapKeys { ChainId(it.key) },
            usedAddresses = if (target is WatchTarget.ExtendedPublicKey) walletDao.usedDerivedAddresses(wallet.id).toSet() else emptySet(),
            trace = { log.log("wallet=${wallet.id} $it") },
        )
        val known = walletDao.knownConfirmedHashes(wallet.id).toSet()
        val result = adapter.sync(target, known, options) { part ->
            storePart(wallet.id, target, part)
            // After the rows: if the app dies in between, a page is merely fetched twice.
            val state = part.state ?: return@sync
            stateLock.withLock {
                syncState = syncState.copy(chains = syncState.chains + (part.chainId.value to state))
                syncStates.save(wallet.id, syncState)
            }
        }
        val now = Clock.System.now().toEpochMilliseconds()
        if (result.storedProgressively) {
            // Adapters that cannot continue a cut-off history never fetch the missing part.
            walletDao.finishSync(wallet.id, now, (wallet.historyTruncated && !adapter.supportsHistoryContinuation) || result.historyTruncated)
        } else {
            storeWhole(wallet, target, adapter, result, now)
        }
        if (result.discoveryCompleted) {
            stateLock.withLock {
                syncState = syncState.copy(discoveredAt = now)
                syncStates.save(wallet.id, syncState)
            }
        }
        return result
    }

    private fun isSpam(asset: com.altaran.watchwallet.core.model.Asset, target: WatchTarget): Boolean =
        asset.id != AssetId.native(asset.chainId) && target.family == ChainFamily.EVM && SpamFilter.isSpam(asset.symbol, asset.name)

    private suspend fun storePart(walletId: Long, target: WatchTarget, part: SyncPart) {
        val now = Clock.System.now().toEpochMilliseconds()
        val assets = (part.balances.map { it.asset } + part.transactions.map { it.asset }).distinctBy { it.id }
        walletDao.storeSyncPart(
            walletId = walletId,
            chainId = part.chainId.value,
            assets = assets.map { it.toEntity(isSpam = isSpam(it, target)) },
            balances = part.balances.filter { !it.amount.isZero }.map { WalletBalanceEntity(walletId, it.asset.id.value, it.amount.raw.toString(), now) },
            refreshedAssets = part.balances.map { it.asset.id.value },
            balancesComplete = part.balancesComplete,
            transactions = part.transactions.map { it.toEntity(walletId) },
            historyFetched = part.historyFetched,
            active = part.active,
        )
    }

    private suspend fun storeWhole(wallet: WalletEntity, target: WatchTarget, adapter: ChainAdapter, result: SyncResult, now: Long) {
        val activeChains = activeChainsAfter(wallet, result)
        val assets = (result.balances.map { it.asset } + result.transactions.map { it.asset }).distinctBy { it.id }
        walletDao.storeSync(
            walletId = wallet.id,
            assets = assets.map { it.toEntity(isSpam = isSpam(it, target)) },
            balances = result.balances.filter { !it.amount.isZero }.map {
                WalletBalanceEntity(wallet.id, it.asset.id.value, it.amount.raw.toString(), now)
            },
            transactions = result.transactions.map { it.toEntity(wallet.id) },
            // Only an extended key derives addresses on this device. Anything else would have come from
            // a server, and version 1.0 stored such rows for Cardano stake wallets: they go here.
            derived = if (target is WatchTarget.ExtendedPublicKey) {
                result.derivedAddresses.map { DerivedAddressEntity(wallet.id, it.address, it.change, it.index, it.used) }
            } else {
                emptyList()
            },
            activeChains = activeChains.joinToString(","),
            syncedAt = now,
            // Adapters that cannot continue a cut-off history never fetch the missing part, so
            // a wallet once truncated stays marked as such.
            truncated = (wallet.historyTruncated && !adapter.supportsHistoryContinuation) || result.historyTruncated,
            balanceChains = result.syncedChains?.map { it.value },
            refreshedAssets = if (result.balancesComplete) null else result.balances.map { it.asset.id.value },
            corrected = result.correctedTransactions.map { it.toEntity(wallet.id) },
        )
    }
}
