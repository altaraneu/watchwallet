package com.altaran.watchwallet.core.data.sync

import com.altaran.watchwallet.chain.evm.SpamFilter
import com.altaran.watchwallet.core.data.chain.ChainRegistry
import com.altaran.watchwallet.core.data.repository.toTarget
import com.altaran.watchwallet.core.database.dao.WalletDao
import com.altaran.watchwallet.core.database.entity.DerivedAddressEntity
import com.altaran.watchwallet.core.database.entity.WalletBalanceEntity
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.database.entity.WalletTxEntity
import com.altaran.watchwallet.core.data.repository.toEntity
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.WatchTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

data class SyncState(
    val running: Boolean = false,
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
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Starts a background sync of all wallets (no-op if one is running) and refreshes prices. */
    fun syncAll(): Job = scope.launch {
        if (mutex.isLocked) return@launch
        mutex.withLock {
            _state.update { it.copy(running = true) }
            try {
                // Wallets sync in parallel (bounded) so one large wallet does not block the others;
                // each result is stored as soon as it is ready.
                val gate = Semaphore(PARALLEL_WALLETS)
                coroutineScope {
                    walletDao.activeWallets().forEach { wallet -> launch { gate.withPermit { syncOne(wallet) } } }
                }
                prices.refreshCurrentPrices()
            } finally {
                _state.update { it.copy(running = false, syncingWallets = emptySet()) }
            }
        }
    }

    fun syncWallet(walletId: Long): Job = scope.launch {
        walletDao.wallet(walletId)?.let { syncOne(it) }
        prices.refreshCurrentPrices()
    }

    private companion object {
        const val PARALLEL_WALLETS = 3
    }

    private suspend fun syncOne(wallet: WalletEntity) {
        val target = wallet.toTarget() ?: return
        val adapter = registry.adapter(target) ?: return
        _state.update { it.copy(syncingWallets = it.syncingWallets + wallet.id) }
        try {
            val known = walletDao.knownConfirmedHashes(wallet.id).toSet()
            val result = adapter.sync(target, known)
            val now = Clock.System.now().toEpochMilliseconds()

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
                transactions = result.transactions.map { tx ->
                    WalletTxEntity(
                        walletId = wallet.id,
                        chainId = tx.chainId.value,
                        hash = tx.hash,
                        assetId = tx.asset.id.value,
                        timestamp = tx.timestamp.toEpochMilliseconds(),
                        delta = tx.delta.toString(),
                        fee = tx.fee?.toString(),
                        isSelf = tx.isSelfTransfer,
                        confirmed = tx.confirmed,
                    )
                },
                derived = if (target is WatchTarget.ExtendedPublicKey) {
                    result.derivedAddresses.map { DerivedAddressEntity(wallet.id, it.address, it.change, it.index, it.used) }
                } else {
                    null
                },
                activeChains = result.activeChains.joinToString(",") { it.value },
                syncedAt = now,
                truncated = result.historyTruncated,
            )
            _state.update { it.copy(errors = it.errors - wallet.id) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            _state.update { it.copy(errors = it.errors + (wallet.id to (e.message ?: e::class.simpleName.orEmpty()))) }
        } finally {
            _state.update { it.copy(syncingWallets = it.syncingWallets - wallet.id) }
        }
    }
}
