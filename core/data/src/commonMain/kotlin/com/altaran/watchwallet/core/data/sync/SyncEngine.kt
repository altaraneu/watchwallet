package com.altaran.watchwallet.core.data.sync

import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.evm.SpamFilter
import com.altaran.watchwallet.core.data.chain.ChainRegistry
import com.altaran.watchwallet.core.data.repository.toEntity
import com.altaran.watchwallet.core.data.repository.toTarget
import com.altaran.watchwallet.core.database.dao.WalletDao
import com.altaran.watchwallet.core.database.entity.DerivedAddressEntity
import com.altaran.watchwallet.core.database.entity.WalletBalanceEntity
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.database.entity.WalletTxEntity
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.WatchTarget
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

data class SyncState(
    val running: Boolean = false,
    /** The running sync was requested by the user (pull to refresh); only then is a spinner shown. */
    val manual: Boolean = false,
    /** Wallet ids currently syncing. */
    val syncingWallets: Set<Long> = emptySet(),
    /** Wallet id → last error (cleared on success). Messages are technical, not shown verbatim. */
    val errors: Map<Long, String> = emptyMap(),
)

/**
 * Fetches balances and history for wallets and stores each result atomically.
 * Wallets are synced one at a time so public APIs are not flooded.
 */
class SyncEngine(
    private val walletDao: WalletDao,
    private val registry: ChainRegistry,
    private val prices: com.altaran.watchwallet.core.data.repository.PriceRepository,
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
                val wallets = walletDao.activeWallets()
                // Errors of wallets that were deleted (or archived) are no longer relevant.
                val ids = wallets.map { it.id }.toSet()
                _state.update { state -> state.copy(errors = state.errors.filterKeys { it in ids }) }
                // Wallets sync in parallel (bounded) so one large wallet does not block the others;
                // each result is stored as soon as it is ready.
                val gate = Semaphore(PARALLEL_WALLETS)
                coroutineScope {
                    wallets.forEach { wallet -> launch { gate.withPermit { syncOne(wallet) } } }
                }
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
        syncOne(wallet)
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

    private suspend fun syncOne(snapshot: WalletEntity) {
        val target = snapshot.toTarget() ?: return
        val adapter = registry.adapter(target) ?: return
        // The same wallet never syncs twice at once (syncAll and syncWallet can overlap).
        if (!inFlightLock.withLock { inFlight.add(snapshot.id) }) return
        _state.update { it.copy(syncingWallets = it.syncingWallets + snapshot.id) }
        // `wallet` is re-read below; the finally block only needs the id.
        val walletId = snapshot.id
        try {
            // Re-read: another sync may have finished since the snapshot (truncation, active chains).
            val wallet = walletDao.wallet(walletId)
            if (wallet == null) {
                _state.update { it.copy(errors = it.errors - walletId) }
                return
            }
            val known = walletDao.knownConfirmedHashes(wallet.id).toSet()
            val result = adapter.sync(target, known, continueHistory = wallet.historyTruncated)
            val now = Clock.System.now().toEpochMilliseconds()

            val activeChains = activeChainsAfter(wallet, result)
            val assets = (result.balances.map { it.asset } + result.transactions.map { it.asset }).distinctBy { it.id }
            walletDao.storeSync(
                walletId = wallet.id,
                assets = assets.map { asset ->
                    val isNative = asset.id == AssetId.native(asset.chainId)
                    asset.toEntity(isSpam = !isNative && target.family == ChainFamily.EVM && SpamFilter.isSpam(asset.symbol, asset.name))
                },
                balances = result.balances.filter { !it.amount.isZero }.map {
                    WalletBalanceEntity(wallet.id, it.asset.id.value, it.amount.raw.toString(), now)
                },
                transactions = result.transactions.map { it.toEntity(wallet.id) },
                derived = if (target is WatchTarget.ExtendedPublicKey) {
                    result.derivedAddresses.map { DerivedAddressEntity(wallet.id, it.address, it.change, it.index, it.used) }
                } else {
                    null
                },
                activeChains = activeChains.joinToString(","),
                syncedAt = now,
                // Adapters that cannot continue a cut-off history never fetch the missing part, so
                // a wallet once truncated stays marked as such.
                truncated = (wallet.historyTruncated && !adapter.supportsHistoryContinuation) || result.historyTruncated,
                balanceChains = result.syncedChains?.map { it.value },
            )
            _state.update { it.copy(errors = it.errors - wallet.id) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            _state.update { it.copy(errors = it.errors + (walletId to (e.message ?: e::class.simpleName.orEmpty()))) }
        } finally {
            _state.update { it.copy(syncingWallets = it.syncingWallets - walletId) }
            withContext(NonCancellable) { inFlightLock.withLock { inFlight.remove(walletId) } }
        }
    }
}
