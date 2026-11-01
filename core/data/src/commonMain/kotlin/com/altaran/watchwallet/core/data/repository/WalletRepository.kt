package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.database.dao.WalletDao
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.model.Wallet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

interface WalletRepository {
    val activeWallets: Flow<List<Wallet>>
    val activeWalletCount: Flow<Int>
}

internal class DefaultWalletRepository(
    private val walletDao: WalletDao,
) : WalletRepository {
    override val activeWallets: Flow<List<Wallet>> =
        walletDao.observeActiveWallets().map { wallets -> wallets.map(WalletEntity::toModel) }

    override val activeWalletCount: Flow<Int> = walletDao.observeActiveWalletCount()
}

private fun WalletEntity.toModel() = Wallet(
    id = id,
    name = name,
    emoji = emoji,
    groupId = groupId,
    notes = notes,
    sortOrder = sortOrder,
    archived = archived,
    createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
)
