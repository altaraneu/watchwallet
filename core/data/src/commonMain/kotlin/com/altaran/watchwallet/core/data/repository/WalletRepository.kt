package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.database.dao.WalletDao
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetBalance
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.DerivedAddress
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.Wallet
import com.altaran.watchwallet.core.model.WalletTransaction
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.security.SecretGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    suspend fun addWallet(target: WatchTarget, name: String, activeChains: List<ChainId>, groupId: Long? = null): Long

    val groups: Flow<List<com.altaran.watchwallet.core.model.WalletGroup>>
    suspend fun createGroup(name: String): Long
    suspend fun findGroup(name: String): Long?
    suspend fun rename(id: Long, name: String)
    suspend fun delete(id: Long)

    /** Removes all wallets, their cached history and all groups. Settings and watchlist stay. */
    suspend fun deleteAll()
    suspend fun setAssetVisibility(asset: Asset, visible: Boolean?)
}

internal class DefaultWalletRepository(
    private val walletDao: WalletDao,
) : WalletRepository {
    override val activeWallets: Flow<List<Wallet>> =
        walletDao.observeActiveWallets().map { wallets -> wallets.map(WalletEntity::toModel) }

    override val activeWalletCount: Flow<Int> = walletDao.observeActiveWalletCount()

    override val balances: Flow<List<BalanceRow>> = walletDao.observeAllBalances().map { rows ->
        rows.map { row ->
            BalanceRow(row.walletId, row.toAsset(), TokenAmount.parseRaw(row.raw, row.decimals), row.isSpam, row.userVisible)
        }
    }

    override val allTransactions: Flow<List<WalletTransaction>> =
        walletDao.observeAllTransactions().map { rows -> rows.map { it.toModel() } }

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

    override fun transactions(walletId: Long): Flow<List<WalletTransaction>> =
        walletDao.observeTransactions(walletId).map { rows -> rows.map { it.toModel() } }

    override fun derivedAddresses(walletId: Long): Flow<List<DerivedAddress>> =
        walletDao.observeDerivedAddresses(walletId).map { rows ->
            rows.map { DerivedAddress(it.address, it.isChange, it.derivationIndex, it.used) }
        }

    override suspend fun findExisting(target: WatchTarget): Long? =
        walletDao.findByTarget(target.family.name, target.storedValue())?.id

    override val groups: Flow<List<com.altaran.watchwallet.core.model.WalletGroup>> =
        walletDao.observeGroups().map { rows -> rows.map { com.altaran.watchwallet.core.model.WalletGroup(it.id, it.name, it.sortOrder) } }

    override suspend fun createGroup(name: String): Long {
        requireNoSecret(name)
        return walletDao.insertGroup(
            com.altaran.watchwallet.core.database.entity.WalletGroupEntity(name = name.trim(), sortOrder = walletDao.nextGroupSortOrder()),
        )
    }

    override suspend fun findGroup(name: String): Long? = walletDao.findGroup(name.trim())

    override suspend fun addWallet(target: WatchTarget, name: String, activeChains: List<ChainId>, groupId: Long?): Long {
        // Defense in depth: never persist anything that looks like key material.
        requireNoSecret(target.storedValue())
        requireNoSecret(name)
        findExisting(target)?.let { return it }
        val entity = WalletEntity(
            name = name.trim(),
            emoji = null,
            groupId = groupId,
            notes = null,
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
        )
        return walletDao.insert(entity)
    }

    override suspend fun rename(id: Long, name: String) {
        requireNoSecret(name)
        walletDao.rename(id, name.trim())
    }

    override suspend fun delete(id: Long) = walletDao.delete(id)

    override suspend fun deleteAll() = walletDao.deleteAllWallets()

    override suspend fun setAssetVisibility(asset: Asset, visible: Boolean?) =
        walletDao.setAssetVisibility(asset.id.value, visible)
}

/** Defense in depth: never persist free text or targets that look like key material. */
private fun requireNoSecret(value: String) {
    require(!SecretGuard.isSecret(value)) { "Refusing to store key material" }
}

/** Converts current balances to the domain type (used by repositories and tests). */
internal fun BalanceRow.toAssetBalance() = AssetBalance(walletId, asset, amount)
