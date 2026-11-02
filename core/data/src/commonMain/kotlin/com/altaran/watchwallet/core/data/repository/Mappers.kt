package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.database.dao.BalanceWithAsset
import com.altaran.watchwallet.core.database.entity.AssetEntity
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.database.entity.WalletTxEntity
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.model.TxDirection
import com.altaran.watchwallet.core.model.Wallet
import com.altaran.watchwallet.core.model.WalletTransaction
import com.altaran.watchwallet.core.model.WatchTarget
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.time.Instant

internal const val KIND_ADDRESS = "ADDRESS"
internal const val KIND_XPUB = "XPUB"

internal fun WalletEntity.toModel(): Wallet = Wallet(
    id = id,
    name = name,
    emoji = emoji,
    groupId = groupId,
    notes = notes,
    sortOrder = sortOrder,
    archived = archived,
    createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
    target = toTarget(),
    activeChains = activeChains.split(',').filter { it.isNotBlank() }.map(::ChainId),
    lastSyncedAt = lastSyncedAt?.let(Instant::fromEpochMilliseconds),
    subtractFromWalletId = subtractFromWalletId,
)

internal fun WalletEntity.toTarget(): WatchTarget? {
    val family = family?.let { value -> ChainFamily.entries.firstOrNull { it.name == value } } ?: return null
    val chain = ChainId(chainId ?: return null)
    val value = targetValue ?: return null
    val script = scriptType?.let { stored -> ScriptType.entries.firstOrNull { it.name == stored } }
    return when (targetKind) {
        KIND_XPUB -> WatchTarget.ExtendedPublicKey(family, chain, value, script ?: return null, gapLimit ?: WatchTarget.DEFAULT_GAP_LIMIT)
        else -> WatchTarget.Address(family, chain, value, script)
    }
}

internal fun WatchTarget.storedValue(): String = when (this) {
    is WatchTarget.Address -> address
    is WatchTarget.ExtendedPublicKey -> key
}

internal fun Asset.toEntity(isSpam: Boolean) = AssetEntity(
    id = id.value,
    chainId = chainId.value,
    symbol = symbol,
    name = name,
    decimals = decimals,
    priceId = priceId,
    contract = contract,
    isSpam = isSpam,
)

internal fun BalanceWithAsset.toAsset() = Asset(
    id = AssetId(id),
    chainId = ChainId(chainId),
    symbol = symbol,
    name = name,
    decimals = decimals,
    priceId = priceId,
    contract = contract,
)

internal fun WalletTxEntity.toModel() = WalletTransaction(
    walletId = walletId,
    chainId = ChainId(chainId),
    hash = hash,
    timestamp = Instant.fromEpochMilliseconds(timestamp),
    assetId = AssetId(assetId),
    delta = BigInteger.parseString(delta),
    fee = fee?.let { BigInteger.parseString(it) },
    direction = when {
        isSelf -> TxDirection.SELF
        delta.startsWith("-") -> TxDirection.OUT
        else -> TxDirection.IN
    },
    confirmed = confirmed,
)
