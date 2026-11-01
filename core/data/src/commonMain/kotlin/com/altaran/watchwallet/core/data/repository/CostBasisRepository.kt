package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.database.dao.WalletDao
import com.altaran.watchwallet.core.database.entity.TxCostBasisEntity
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.TxDirection
import com.altaran.watchwallet.core.model.WalletTransaction
import com.altaran.watchwallet.core.timeline.TimedPrice
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Identifies one transaction leg (a transaction can move several assets). */
data class CostKey(val walletId: Long, val hash: String, val assetId: String)

data class CostBasisEntry(val valueUsd: Double, val userSet: Boolean)

/** Cost basis of a current holding (average cost method) in USD. */
data class HoldingCost(val costUsd: Double, val complete: Boolean)

/**
 * Cost basis of incoming transfers, in USD. Each incoming transfer is valued automatically with
 * the USD price on its day; the user can replace that with what the coins really cost (for
 * example bought on an exchange years ago and only now moved to this wallet). User values are
 * stored as irreplaceable data and never overwritten.
 */
class CostBasisRepository(
    private val walletDao: WalletDao,
    private val priceRepository: PriceRepository,
) {
    private val fillMutex = Mutex()

    val entries: Flow<Map<CostKey, CostBasisEntry>> = walletDao.observeCostBasis().map { rows ->
        rows.associate { CostKey(it.walletId, it.hash, it.assetId) to CostBasisEntry(it.valueUsd, it.userSet) }
    }

    /** Values incoming transfers that have no cost basis yet with the USD price of their day. */
    suspend fun fillAutomatic() = fillMutex.withLock {
        val existing = walletDao.costBasis().map { CostKey(it.walletId, it.hash, it.assetId) }.toSet()
        val missing = walletDao.observeAllTransactions().first().filter { tx ->
            tx.confirmed && !tx.isSelf && !tx.delta.startsWith("-") && tx.delta != "0" &&
                CostKey(tx.walletId, tx.hash, tx.assetId) !in existing
        }
        if (missing.isEmpty()) return@withLock
        val assets = walletDao.observeAssets().first().associateBy { it.id }
        val priceIds = missing.mapNotNull { assets[it.assetId]?.priceId }.distinct()
        if (priceIds.isEmpty()) return@withLock
        priceRepository.ensureHistory(priceIds, QuoteCurrency.USD, ChartRange.ALL)
        val history = priceRepository.history(priceIds, QuoteCurrency.USD).first()
        val now = Clock.System.now().toEpochMilliseconds()
        val rows = missing.mapNotNull { tx ->
            val asset = assets[tx.assetId] ?: return@mapNotNull null
            val price = priceOnDay(history[asset.priceId].orEmpty(), Instant.fromEpochMilliseconds(tx.timestamp)) ?: return@mapNotNull null
            val amount = TokenAmount(BigInteger.parseString(tx.delta), asset.decimals).toDouble()
            TxCostBasisEntity(tx.walletId, tx.hash, tx.assetId, amount * price, userSet = false, updatedAt = now)
        }
        walletDao.upsertCostBasis(rows)
    }

    /** Stores what the transfer really cost (USD), or with `null` returns to the automatic value. */
    suspend fun setUserValue(key: CostKey, valueUsd: Double?) {
        if (valueUsd == null) {
            walletDao.deleteCostBasis(key.walletId, key.hash, key.assetId)
            fillAutomatic()
        } else {
            walletDao.upsertCostBasis(
                listOf(TxCostBasisEntity(key.walletId, key.hash, key.assetId, valueUsd, userSet = true, Clock.System.now().toEpochMilliseconds())),
            )
        }
    }

    companion object {
        /** Prices older than this before the transfer are not used for its value. */
        private val MAX_PRICE_AGE = 3.days

        /** Last price at or before [time], if it is recent enough to stand for that day. */
        fun priceOnDay(prices: List<TimedPrice>, time: Instant): Double? {
            val point = prices.lastOrNull { it.time <= time } ?: return null
            return point.price.takeIf { time - point.time <= MAX_PRICE_AGE }
        }

        /**
         * Cost of what is held now, average cost method: incoming transfers add their cost,
         * outgoing ones (fees included) remove cost in proportion to the amount that left.
         * [amountOf] converts a transaction delta to a decimal amount.
         */
        fun holdingCost(transactions: List<WalletTransaction>, costs: Map<CostKey, CostBasisEntry>, amountOf: (WalletTransaction) -> Double): HoldingCost {
            var quantity = 0.0
            var cost = 0.0
            var complete = true
            transactions.sortedBy { it.timestamp }.forEach { tx ->
                val amount = amountOf(tx)
                if (amount > 0 && tx.direction == TxDirection.IN) {
                    val entry = costs[CostKey(tx.walletId, tx.hash, tx.assetId.value)]
                    if (entry == null) complete = false else cost += entry.valueUsd
                    quantity += amount
                } else if (amount < 0) {
                    if (quantity > 0) cost *= ((quantity + amount) / quantity).coerceIn(0.0, 1.0)
                    quantity = (quantity + amount).coerceAtLeast(0.0)
                }
            }
            return HoldingCost(cost, complete)
        }
    }
}
