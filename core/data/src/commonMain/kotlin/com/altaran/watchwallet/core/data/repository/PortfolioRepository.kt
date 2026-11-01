package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.model.WalletTransaction
import com.altaran.watchwallet.core.pricing.CurrentPrice
import com.altaran.watchwallet.core.timeline.AssetHistory
import com.altaran.watchwallet.core.timeline.TimedDelta
import com.altaran.watchwallet.core.timeline.TimedPrice
import com.altaran.watchwallet.core.timeline.TimedValue
import com.altaran.watchwallet.core.timeline.Timeline
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.time.Clock

/** One asset aggregated across wallets and chains (ETH on Ethereum + Base = one ETH holding). */
data class Holding(
    /** Price id when known, otherwise the asset id. */
    val key: String,
    val symbol: String,
    val name: String,
    val priceId: String?,
    val amount: Double,
    val price: Double?,
    val change24hPercent: Double?,
    val value: Double?,
    val assets: List<Asset>,
    val walletIds: Set<Long>,
)

/**
 * Price ids worth fetching chart history for: priced holdings only (unpriced spam tokens have no
 * history), largest first so the chart fills in with what matters while the rest loads.
 */
fun List<Holding>.historyPriceIds(): List<String> =
    filter { it.price != null }.sortedByDescending { it.value ?: 0.0 }.mapNotNull { it.priceId }.distinct()

data class PortfolioSnapshot(
    val totalValue: Double,
    /** Value change over 24 hours, from current holdings and 24h price changes. */
    val change24hValue: Double,
    val change24hPercent: Double?,
    val holdings: List<Holding>,
    val hiddenAssetCount: Int,
    val hasUnpricedAssets: Boolean,
    /** Hidden assets (spam, no price, or hidden by the user), to review and show again. */
    val hiddenAssets: List<HiddenAsset> = emptyList(),
)

/** An asset left out of totals; [assets] are the per-chain entries to change visibility for. */
data class HiddenAsset(val key: String, val symbol: String, val name: String, val amount: Double, val assets: List<Asset>, val isSpam: Boolean)

/**
 * Portfolio-level view: holdings, total value and value history. Pure combination of cached
 * data; network refreshes are triggered separately (sync engine, price repository).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioRepository(
    private val walletRepository: WalletRepository,
    private val priceRepository: PriceRepository,
) {
    /** Visible balance rows, optionally limited to some wallets. */
    private fun visibleBalances(currency: QuoteCurrency, walletIds: Set<Long>?): Flow<Pair<List<BalanceRow>, Map<String, CurrentPrice>>> =
        combine(walletRepository.balances, priceRepository.currentPrices(currency)) { balances, prices ->
            val filtered = balances.filter { walletIds == null || it.walletId in walletIds }
            filtered.filter { isVisible(it, prices) } to prices
        }

    fun snapshot(currency: QuoteCurrency, walletIds: Set<Long>? = null): Flow<PortfolioSnapshot> =
        combine(walletRepository.balances, priceRepository.currentPrices(currency)) { balances, prices ->
            val scoped = balances.filter { walletIds == null || it.walletId in walletIds }
            val (visible, hidden) = scoped.partition { isVisible(it, prices) }
            val holdings = aggregate(visible, prices)
            val total = holdings.sumOf { it.value ?: 0.0 }
            val change = holdings.sumOf { holding ->
                val value = holding.value ?: return@sumOf 0.0
                val pct = holding.change24hPercent ?: return@sumOf 0.0
                value - value / (1 + pct / PERCENT)
            }
            PortfolioSnapshot(
                totalValue = total,
                change24hValue = change,
                change24hPercent = if (total - change > 0) change / (total - change) * PERCENT else null,
                holdings = holdings,
                hiddenAssetCount = hidden.map { it.asset.id }.distinct().size,
                hasUnpricedAssets = holdings.any { it.price == null },
                hiddenAssets = hidden.groupBy { it.asset.id }.map { (_, rows) ->
                    val asset = rows.first().asset
                    HiddenAsset(
                        key = asset.id.value,
                        symbol = asset.symbol,
                        name = asset.name,
                        amount = rows.sumOf { it.amount.toDouble() },
                        assets = listOf(asset),
                        isSpam = rows.first().isSpam,
                    )
                }.sortedBy { it.symbol.lowercase() },
            )
        }

    /** Current value of every wallet (visible assets only). */
    fun walletValues(currency: QuoteCurrency): Flow<Map<Long, Double>> =
        combine(walletRepository.balances, priceRepository.currentPrices(currency)) { balances, prices ->
            balances.filter { isVisible(it, prices) }.groupBy { it.walletId }.mapValues { (_, rows) ->
                rows.sumOf { row -> row.asset.priceId?.let { prices[it]?.price }?.let { it * row.amount.toDouble() } ?: 0.0 }
            }
        }

    /** Total value over time for the range (all wallets or a subset), downsampled for rendering. */
    fun valueSeries(currency: QuoteCurrency, range: ChartRange, walletIds: Set<Long>? = null): Flow<List<TimedValue>> =
        visibleBalances(currency, walletIds).flatMapLatest { (balances, prices) ->
            val priceIds = balances.mapNotNull { it.asset.priceId }.distinct()
            combine(
                walletRepository.allTransactions,
                priceRepository.history(priceIds, currency).onStart { emit(emptyMap()) },
            ) { transactions, history ->
                val histories = buildHistories(balances, transactions.filter { walletIds == null || it.walletId in walletIds }, history, prices)
                val first = transactions.minOfOrNull { it.timestamp }
                val (samples, rangeStart) = displaySamples(range, first)
                displayDownsample(Timeline.portfolio(histories, samples), rangeStart)
            }
        }.distinctUntilChanged()

    /**
     * Sample times for a chart of [range] that can be panned back in time: the selected range at
     * its own resolution, preceded by daily samples of the whole history. Returns the samples and
     * the start of the selected range (the initially visible window).
     */
    private fun displaySamples(range: ChartRange, firstActivity: kotlin.time.Instant?): Pair<List<kotlin.time.Instant>, kotlin.time.Instant> {
        val now = Clock.System.now()
        val fine = Timeline.sampleTimes(range, now, firstActivity)
        val start = fine.firstOrNull() ?: now
        if (range == ChartRange.ALL) return fine to start
        val coarse = Timeline.sampleTimes(ChartRange.ALL, now, firstActivity).filter { it < start }
        return (coarse + fine) to start
    }

    /** Downsamples only the older, coarse part, so the selected range keeps its resolution. */
    private fun displayDownsample(points: List<TimedValue>, rangeStart: kotlin.time.Instant): List<TimedValue> {
        val (older, inRange) = points.partition { it.time < rangeStart }
        return Timeline.downsample(older, maxPoints = OLDER_POINTS) + Timeline.downsample(inRange)
    }

    /** Balance and value history of one holding (by [Holding.key]). */
    fun holdingSeries(currency: QuoteCurrency, holdingKey: String, range: ChartRange): Flow<HoldingSeries> =
        visibleBalances(currency, null).flatMapLatest { (balances, prices) ->
            val rows = balances.filter { keyOf(it.asset) == holdingKey }
            val priceIds = rows.mapNotNull { it.asset.priceId }.distinct()
            combine(walletRepository.allTransactions, priceRepository.history(priceIds, currency).onStart { emit(emptyMap()) }) { txs, history ->
                val histories = buildHistories(rows, txs, history, prices)
                val (samples, rangeStart) = displaySamples(
                    range,
                    txs.filter { tx ->
                        rows.any { it.asset.id == tx.assetId }
                    }.minOfOrNull { it.timestamp },
                )
                val balanceSeries = samples.indices.map { i ->
                    TimedValue(samples[i], histories.sumOf { Timeline.balances(it, samples)[i] })
                }
                HoldingSeries(
                    balance = displayDownsample(balanceSeries, rangeStart),
                    value = displayDownsample(Timeline.portfolio(histories, samples), rangeStart),
                    price = Timeline.downsample(
                        priceIds.firstOrNull()?.let { id ->
                            history[id].orEmpty().filter { it.time >= samples.first() }.map { TimedValue(it.time, it.price) }
                        }.orEmpty(),
                    ),
                )
            }
        }

    /** Price ids needed to draw [range] for everything visible; used to trigger history fetches. */
    suspend fun ensureHistory(currency: QuoteCurrency, range: ChartRange, priceIds: Collection<String>) =
        priceRepository.ensureHistory(priceIds, currency, range)

    private fun buildHistories(
        balances: List<BalanceRow>,
        transactions: List<WalletTransaction>,
        history: Map<String, List<TimedPrice>>,
        prices: Map<String, CurrentPrice>,
    ): List<AssetHistory> {
        val txByWalletAsset = transactions.groupBy { it.walletId to it.assetId }
        return balances.map { row ->
            AssetHistory(
                currentBalance = row.amount.raw,
                decimals = row.amount.decimals,
                deltas = txByWalletAsset[row.walletId to row.asset.id].orEmpty().map { TimedDelta(it.timestamp, it.delta) },
                prices = row.asset.priceId?.let { history[it] }.orEmpty(),
                currentPrice = row.asset.priceId?.let { prices[it]?.price },
            )
        }
    }

    private fun aggregate(rows: List<BalanceRow>, prices: Map<String, CurrentPrice>): List<Holding> =
        rows.groupBy { keyOf(it.asset) }.map { (key, group) ->
            val first = group.first().asset
            val price = first.priceId?.let { prices[it] }
            val amount = group.sumOf { it.amount.toDouble() }
            Holding(
                key = key,
                symbol = first.symbol,
                name = first.name,
                priceId = first.priceId,
                amount = amount,
                price = price?.price,
                change24hPercent = price?.change24hPercent,
                value = price?.price?.let { it * amount },
                assets = group.map { it.asset }.distinctBy { it.id },
                walletIds = group.map { it.walletId }.toSet(),
            )
        }.sortedWith(compareByDescending<Holding> { it.value ?: -1.0 }.thenBy { it.symbol })

    companion object {
        /** Points kept for the history before the selected range (panning back). */
        private const val OLDER_POINTS = 400

        private const val PERCENT = 100.0

        fun keyOf(asset: Asset): String = asset.priceId ?: asset.id.value

        /**
         * Visibility: the user's explicit choice wins; otherwise spam is hidden, native coins are
         * shown, and tokens are shown only when they have a price.
         */
        fun isVisible(row: BalanceRow, prices: Map<String, CurrentPrice>): Boolean = row.userVisible
            ?: when {
                row.isSpam -> false
                row.asset.id == AssetId.native(row.asset.chainId) -> true
                else -> row.asset.priceId?.let { prices.containsKey(it) } == true
            }
    }
}

data class HoldingSeries(
    val balance: List<TimedValue>,
    val value: List<TimedValue>,
    val price: List<TimedValue>,
)

/** Exposed for UI formatting of raw amounts. */
fun BigInteger.isNegative(): Boolean = this < BigInteger.ZERO
