package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.TxDirection
import com.altaran.watchwallet.core.model.Wallet
import com.altaran.watchwallet.core.model.WalletTransaction
import com.altaran.watchwallet.core.pricing.CurrentPrice
import com.altaran.watchwallet.core.timeline.AssetHistory
import com.altaran.watchwallet.core.timeline.TimedDelta
import com.altaran.watchwallet.core.timeline.TimedPrice
import com.altaran.watchwallet.core.timeline.Timeline
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.pow
import kotlin.time.Instant

/**
 * Where a current balance without transactions of its own came from (staked coins, rewards, chains
 * without history). Projecting it flat over the whole chart would invent value before it existed:
 *
 * - Stake moves are a pair: a self-transfer of the native coin into or out of a stake account is
 *   the opposite move of the staked asset, and the balance starts at the first of them.
 * - Otherwise it starts at the wallet's first transaction on that chain: it cannot be older.
 * - On a chain without any known transaction it starts when the wallet was added, the first time it
 *   was seen. Nothing says how it got there, so it counts as a deposit, not as a gain.
 * - A wallet marked as truncated may really hold coins from before its known history, so such a
 *   balance is projected back as before; stake moves still apply.
 *
 * Assets with transactions of their own are not touched: their balances are rebuilt from those.
 */
internal class BalanceOrigin(
    val deltas: List<TimedDelta> = emptyList(),
    val startsAt: Instant? = null,
    val countsAsDeposit: Boolean = false,
)

private const val STAKED_CONTRACT_PREFIX = "staked-"

internal fun balanceOrigin(row: ChartRow, walletTransactions: List<WalletTransaction>, wallet: Wallet?): BalanceOrigin {
    if (row.balance.isZero() || walletTransactions.any { it.assetId == row.asset.id }) return BalanceOrigin()
    val chainTransactions = walletTransactions.filter { it.chainId == row.asset.chainId }
    val stakeMoves = if (row.asset.contract?.startsWith(STAKED_CONTRACT_PREFIX) == true) stakeMoves(row, chainTransactions) else emptyList()
    return when {
        wallet == null || wallet.historyTruncated -> BalanceOrigin(stakeMoves)
        stakeMoves.isNotEmpty() -> BalanceOrigin(stakeMoves, stakeMoves.minOf { it.time })
        chainTransactions.isNotEmpty() -> BalanceOrigin(startsAt = chainTransactions.minOf { it.timestamp })
        else -> BalanceOrigin(startsAt = wallet.createdAt, countsAsDeposit = true)
    }
}

/** Native coins leaving the wallet for its stake account arrive there, without the network fee. */
private fun stakeMoves(row: ChartRow, chainTransactions: List<WalletTransaction>): List<TimedDelta> {
    val native = AssetId.native(row.asset.chainId)
    return chainTransactions.filter { it.direction == TxDirection.SELF && it.assetId == native }.mapNotNull { tx ->
        val moved = -(tx.delta + (tx.fee ?: BigInteger.ZERO))
        if (moved.isZero()) null else TimedDelta(tx.timestamp, moved)
    }
}

internal fun buildHistories(
    rows: List<ChartRow>,
    transactions: List<WalletTransaction>,
    history: Map<String, List<TimedPrice>>,
    prices: Map<String, CurrentPrice>,
    wallets: Map<Long, Wallet>,
): List<AssetHistory> {
    val byWallet = transactions.groupBy { it.walletId }
    return rows.map { row ->
        val walletTransactions = byWallet[row.walletId].orEmpty()
        val origin = balanceOrigin(row, walletTransactions, wallets[row.walletId])
        val own = walletTransactions.filter { it.assetId == row.asset.id }.map { TimedDelta(it.timestamp, it.delta) }
        AssetHistory(
            currentBalance = row.balance,
            decimals = row.decimals,
            deltas = own + origin.deltas,
            prices = row.asset.priceId?.let { history[it] }.orEmpty(),
            currentPrice = row.asset.priceId?.let { prices[it]?.price },
            startsAt = origin.startsAt,
        )
    }
}

/**
 * Net deposits up to each sample: incoming transfers add their value at that time, outgoing ones
 * subtract it (fees excluded: they are a cost, not a withdrawal). Transfers between the user's own
 * wallets cancel out. Only charted assets are counted. Prices as in the value of the same time: the
 * last at or before it, the first one for earlier times, and without any history the current price.
 */
internal fun cumulativeFlows(
    transactions: List<WalletTransaction>,
    rows: List<ChartRow>,
    history: Map<String, List<TimedPrice>>,
    wallets: Map<Long, Wallet>,
    samples: List<Instant>,
    currentPrices: Map<String, CurrentPrice> = emptyMap(),
): DoubleArray {
    fun priceOf(priceId: String?, time: Instant) = priceId?.let { Timeline.priceAt(history[it].orEmpty(), time) ?: currentPrices[it]?.price }

    // Exactly the (wallet, asset) pairs that are charted, so values and flows stay consistent.
    val assets = rows.associate { (it.walletId to it.asset.id) to it.asset }
    val transfers = transactions.filter { it.confirmed && it.direction != TxDirection.SELF }.mapNotNull { tx ->
        val asset = assets[tx.walletId to tx.assetId] ?: return@mapNotNull null
        val price = priceOf(asset.priceId, tx.timestamp) ?: return@mapNotNull null
        val scale = 10.0.pow(asset.decimals)
        val fee = tx.fee?.takeIf { tx.delta.isNegative }?.doubleValue(exactRequired = false) ?: 0.0
        tx.timestamp to (tx.delta.doubleValue(exactRequired = false) + fee) / scale * price
    }
    val byWallet = transactions.groupBy { it.walletId }
    val firstSeen = rows.mapNotNull { row ->
        val origin = balanceOrigin(row, byWallet[row.walletId].orEmpty(), wallets[row.walletId])
        val start = origin.startsAt?.takeIf { origin.countsAsDeposit } ?: return@mapNotNull null
        val price = priceOf(row.asset.priceId, start) ?: return@mapNotNull null
        start to row.balance.doubleValue(exactRequired = false) / 10.0.pow(row.decimals) * price
    }
    val events = (transfers + firstSeen).sortedBy { it.first }
    val result = DoubleArray(samples.size)
    var running = 0.0
    var index = 0
    samples.forEachIndexed { i, sample ->
        // A balance starting at a sample is zero there; so is its deposit, or they would not match.
        while (index < events.size && events[index].first <= sample) {
            running += events[index].second
            index++
        }
        result[i] = running
    }
    return result
}
