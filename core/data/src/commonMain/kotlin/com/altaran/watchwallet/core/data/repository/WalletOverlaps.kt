package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.TxDirection
import com.altaran.watchwallet.core.model.WalletTransaction
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * An address can be tracked on its own while it also belongs to an extended key wallet. With a
 * link (address wallet to extended key wallet) the address is taken out of the larger wallet, so
 * each wallet shows only its own part and the portfolio counts the coins once. Applied when data
 * is read: what is stored stays exactly what the chain reported, and removing a link restores it.
 */
internal object WalletOverlaps {
    fun balances(rows: List<BalanceRow>, links: Map<Long, Long>): List<BalanceRow> {
        if (links.isEmpty()) return rows
        val subtract = HashMap<Pair<Long, AssetId>, BigInteger>()
        rows.forEach { row ->
            val parent = links[row.walletId] ?: return@forEach
            val key = parent to row.asset.id
            subtract[key] = (subtract[key] ?: BigInteger.ZERO) + row.amount.raw
        }
        return rows.mapNotNull { row ->
            val part = subtract[row.walletId to row.asset.id] ?: return@mapNotNull row
            val rest = row.amount.raw - part
            if (rest.isNegative || rest.isZero()) null else row.copy(amount = row.amount.copy(raw = rest))
        }
    }

    fun transactions(rows: List<WalletTransaction>, links: Map<Long, Long>): List<WalletTransaction> {
        if (links.isEmpty()) return rows
        val subtract = HashMap<Triple<Long, String, AssetId>, BigInteger>()
        rows.forEach { tx ->
            val parent = links[tx.walletId] ?: return@forEach
            val key = Triple(parent, tx.hash, tx.assetId)
            subtract[key] = (subtract[key] ?: BigInteger.ZERO) + tx.delta
        }
        return rows.mapNotNull { tx ->
            val part = subtract[Triple(tx.walletId, tx.hash, tx.assetId)] ?: return@mapNotNull tx
            val rest = tx.delta - part
            when {
                rest.isZero() -> null

                // The fee stays with the address wallet's leg of the same transaction.
                else -> tx.copy(delta = rest, fee = null, direction = if (rest.isNegative) TxDirection.OUT else TxDirection.IN)
            }
        }
    }
}
