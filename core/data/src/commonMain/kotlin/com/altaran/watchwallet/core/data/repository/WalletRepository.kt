package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.database.dao.WalletDao
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetBalance
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.DerivedAddress
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.Wallet
import com.altaran.watchwallet.core.model.WalletTransaction
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.security.SecretCheck
import com.altaran.watchwallet.core.security.SecretGuard
import com.altaran.watchwallet.core.security.SecretRejectedException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** A wallet balance row with the asset's visibility decision already applied. */
data class BalanceRow(
    val walletId: Long,
    val asset: Asset,
    val amount: TokenAmount,
    val isSpam: Boolean,
    /** `null` = automatic. */
    val userVisible: Boolean?,
)

/**
 * Functions that store names, groups, notes or targets throw
 * [com.altaran.watchwallet.core.security.SecretRejectedException] for anything that looks like key
 * material. Reading and syncing wallets that are already stored is never checked or blocked.
 */
interface WalletRepository {
    val activeWallets: Flow<List<Wallet>>
    val activeWalletCount: Flow<Int>
    val balances: Flow<List<BalanceRow>>
    val allTransactions: Flow<List<WalletTransaction>>
    val assets: Flow<Map<com.altaran.watchwallet.core.model.AssetId, Asset>>

    fun wallet(id: Long): Flow<Wallet?>
    fun transactions(walletId: Long): Flow<List<WalletTransaction>>
    fun derivedAddresses(walletId: Long): Flow<List<DerivedAddress>>

    /** Returns the id of the existing wallet when the same target is already tracked. */
    suspend fun findExisting(target: WatchTarget): Long?

    /** The extended key wallet that already contains this address, if any. */
    suspend fun findContaining(target: WatchTarget): Wallet?

    /** Links an address wallet to the wallet that contains it, or with `null` removes the link. */
    suspend fun setSubtractFrom(walletId: Long, parentId: Long?)

    suspend fun addWallet(
        target: WatchTarget,
        name: String,
        activeChains: List<ChainId>,
        groupId: Long? = null,
        notes: String? = null,
        subtractFrom: Long? = null,
    ): Long

    val groups: Flow<List<com.altaran.watchwallet.core.model.WalletGroup>>
    suspend fun createGroup(name: String): Long
    suspend fun findGroup(name: String): Long?
    suspend fun rename(id: Long, name: String)
    suspend fun delete(id: Long)

    /** Removes all wallets, their cached history and all groups. Settings and watchlist stay. */
    suspend fun deleteAll()
    suspend fun setAssetVisibility(asset: Asset, visible: Boolean?)
}

/**
 * @param afterDelete runs after a wallet is gone, to clear what the database delete cannot reach.
 */
internal class DefaultWalletRepository(
    private val walletDao: WalletDao,
    private val afterDelete: suspend () -> Unit = {},
) : WalletRepository {
    override val activeWallets: Flow<List<Wallet>> =
        walletDao.observeActiveWallets().map { wallets -> wallets.map(WalletEntity::toModel) }

    override val activeWalletCount: Flow<Int> = walletDao.observeActiveWalletCount()

    /** Address wallet id to the id of the wallet it is subtracted from. */
    private val overlapLinks: Flow<Map<Long, Long>> = walletDao.observeActiveWallets().map { wallets ->
        wallets.mapNotNull { wallet -> wallet.subtractFromWalletId?.let { wallet.id to it } }.toMap()
    }.distinctUntilChanged()

    override val balances: Flow<List<BalanceRow>> = combine(walletDao.observeAllBalances(), overlapLinks) { rows, links ->
        WalletOverlaps.balances(
            rows.map { row ->
                BalanceRow(row.walletId, row.toAsset(), TokenAmount.parseRaw(row.raw, row.decimals), row.isSpam, row.userVisible)
            },
            links,
        )
    }

    override val allTransactions: Flow<List<WalletTransaction>> = combine(walletDao.observeAllTransactions(), overlapLinks) { rows, links ->
        WalletOverlaps.transactions(rows.map { it.toModel() }, links)
    }

    override val assets: Flow<Map<com.altaran.watchwallet.core.model.AssetId, Asset>> =
        walletDao.observeAssets().map { rows ->
            rows.associate { row ->
                com.altaran.watchwallet.core.model.AssetId(row.id) to Asset(
                    id = com.altaran.watchwallet.core.model.AssetId(row.id),
                    chainId = ChainId(row.chainId),
                    symbol = row.symbol,
                    name = row.name,
                    decimals = row.decimals,
                    priceId = row.priceId,
                    contract = row.contract,
                )
            }
        }

    override fun wallet(id: Long): Flow<Wallet?> = walletDao.observeWallet(id).map { it?.toModel() }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun transactions(walletId: Long): Flow<List<WalletTransaction>> = overlapLinks.flatMapLatest { links ->
        if (walletId in links.values) {
            // A wallet with linked addresses needs their transactions too.
            allTransactions.map { rows -> rows.filter { it.walletId == walletId }.sortedByDescending { it.timestamp } }
        } else {
            walletDao.observeTransactions(walletId).map { rows -> rows.map { it.toModel() } }
        }
    }

    override fun derivedAddresses(walletId: Long): Flow<List<DerivedAddress>> =
        walletDao.observeDerivedAddresses(walletId).map { rows ->
            rows.map { DerivedAddress(it.address, it.isChange, it.derivationIndex, it.used) }
        }

    override suspend fun findExisting(target: WatchTarget): Long? =
        walletDao.findByTarget(target.family.name, target.storedValue())?.id

    override suspend fun findContaining(target: WatchTarget): Wallet? = (target as? WatchTarget.Address)?.let {
        walletDao.findByDerivedAddress(target.family.name, it.address)?.toModel()
    }

    override suspend fun setSubtractFrom(walletId: Long, parentId: Long?) = walletDao.setSubtractFrom(walletId, parentId)

    override val groups: Flow<List<com.altaran.watchwallet.core.model.WalletGroup>> =
        walletDao.observeGroups().map { rows -> rows.map { com.altaran.watchwallet.core.model.WalletGroup(it.id, it.name, it.sortOrder) } }

    override suspend fun createGroup(name: String): Long {
        requireNoSecret(name)
        return walletDao.insertGroup(
            com.altaran.watchwallet.core.database.entity.WalletGroupEntity(name = name.trim(), sortOrder = walletDao.nextGroupSortOrder()),
        )
    }

    override suspend fun findGroup(name: String): Long? = walletDao.findGroup(name.trim())

    override suspend fun addWallet(
        target: WatchTarget,
        name: String,
        activeChains: List<ChainId>,
        groupId: Long?,
        notes: String?,
        subtractFrom: Long?,
    ): Long {
        notes?.let { requireNoSecret(it) }
        // Defense in depth: never persist anything that looks like key material.
        requireStorableTarget(target)
        requireNoSecret(name)
        findExisting(target)?.let { return it }
        val entity = WalletEntity(
            name = name.trim(),
            emoji = null,
            groupId = groupId,
            notes = notes,
            sortOrder = walletDao.nextSortOrder(),
            archived = false,
            createdAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            family = target.family.name,
            chainId = when (target) {
                is WatchTarget.Address -> target.chainId.value
                is WatchTarget.ExtendedPublicKey -> target.chainId.value
            },
            targetKind = if (target is WatchTarget.ExtendedPublicKey) KIND_XPUB else KIND_ADDRESS,
            targetValue = target.storedValue(),
            scriptType = when (target) {
                is WatchTarget.Address -> target.scriptType?.name
                is WatchTarget.ExtendedPublicKey -> target.scriptType.name
            },
            gapLimit = (target as? WatchTarget.ExtendedPublicKey)?.gapLimit,
            activeChains = activeChains.joinToString(",") { it.value },
            subtractFromWalletId = subtractFrom,
        )
        return walletDao.insert(entity)
    }

    override suspend fun rename(id: Long, name: String) {
        requireNoSecret(name)
        walletDao.rename(id, name.trim())
    }

    override suspend fun delete(id: Long) {
        walletDao.delete(id)
        finishDeletion()
    }

    override suspend fun deleteAll() {
        walletDao.deleteAllWallets()
        finishDeletion()
    }

    /** The screen that asked is usually leaving, which must not stop this. A failure changes nothing visible. */
    private suspend fun finishDeletion() = withContext(NonCancellable) {
        runCatching { afterDelete() }
        Unit
    }

    override suspend fun setAssetVisibility(asset: Asset, visible: Boolean?) =
        walletDao.setAssetVisibility(asset.id.value, visible)
}

/** Emits whenever the number went down, that is when a wallet was removed. */
fun Flow<Int>.decreases(): Flow<Unit> = runningFold(null as Pair<Int?, Int>?) { previous, count -> previous?.second to count }
    .filter { step -> step != null && (step.first ?: step.second) > step.second }
    .map { }

/** Defense in depth: never persist free text or targets that look like key material. */
private fun requireNoSecret(value: String) = throwIfSecret(SecretGuard.check(value))

/** Sui and Aptos addresses have the form of a hex key and reach this point only through their own route. */
private fun requireStorableTarget(target: WatchTarget) {
    val deliberate = target is WatchTarget.Address && (target.family == ChainFamily.SUI || target.family == ChainFamily.APTOS)
    val value = target.storedValue()
    throwIfSecret(if (deliberate) SecretGuard.checkDeliberateAddress(value) else SecretGuard.check(value))
}

private fun throwIfSecret(check: SecretCheck) {
    if (check is SecretCheck.Secret) throw SecretRejectedException(check.kind)
}

/** Converts current balances to the domain type (used by repositories and tests). */
internal fun BalanceRow.toAssetBalance() = AssetBalance(walletId, asset, amount)
