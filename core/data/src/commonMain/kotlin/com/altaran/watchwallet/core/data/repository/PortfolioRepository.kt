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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.math.pow
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
    fun valueSeries(currency: QuoteCurrency, range: ChartRange, walletIds: Set<Long>? = null): Flow<List<TimedValue>> {
        val inputs = chartInputs(currency, walletIds)
        // Restart only when the set of assets changes; balance and price updates (every sync and
        // minute) just trigger a recomputation, so a long computation is never cancelled forever.
        return inputs.map { it.priceIds }.distinctUntilChanged().flatMapLatest { priceIds ->
            combine(
                inputs,
                priceRepository.history(priceIds, currency).onStart { emit(emptyMap()) },
            ) { input, history ->
                val histories = buildHistories(input.rows, input.transactions, history, input.prices)
                val first = input.transactions.minOfOrNull { it.timestamp }
                val (samples, rangeStart) = displaySamples(range, first)
                val flows = cumulativeFlows(input.transactions, input.rows, history, samples)
                val values = Timeline.portfolio(histories, samples).mapIndexed { i, point -> point.copy(flow = flows[i]) }
                displayDownsample(values, rangeStart)
            }.conflate()
        }.distinctUntilChanged()
            // Hundreds of samples over years of history: never compute on the main thread, and
            // skip intermediate states while prices stream in.
            .conflate()
            .flowOn(Dispatchers.Default)
    }

    /**
     * Rows to chart: every visible current balance plus every (wallet, asset) that only has
     * transactions (sold or moved away), with a current balance of zero, so past values do not
     * vanish when an asset is no longer held. The same rows drive values and deposit flows.
     */
    private fun chartInputs(currency: QuoteCurrency, walletIds: Set<Long>?): Flow<ChartInputs> = combine(
        walletRepository.balances,
        priceRepository.currentPrices(currency),
        walletRepository.allTransactions,
        walletRepository.assets,
    ) { balances, prices, transactions, assets ->
        val scopedTxs = transactions.filter { walletIds == null || it.walletId in walletIds }
        ChartInputs(chartRows(balances, prices, scopedTxs, assets, walletIds), scopedTxs, prices)
    }

    private fun chartRows(
        balances: List<BalanceRow>,
        prices: Map<String, CurrentPrice>,
        transactions: List<WalletTransaction>,
        assets: Map<AssetId, Asset>,
        walletIds: Set<Long>?,
    ): List<ChartRow> {
        // Visibility is decided per asset (the user's choice and spam flag are asset-wide).
        val (visible, hidden) = balances.partition { isVisible(it, prices) }
        val visibleAssets = visible.map { it.asset.id }.toSet()
        val hiddenAssets = hidden.map { it.asset.id }.toSet() - visibleAssets
        val scopedBalances = balances.filter { walletIds == null || it.walletId in walletIds }
        val held = scopedBalances.map { it.walletId to it.asset.id }.toSet()
        val rows = scopedBalances.filter { it.asset.id in visibleAssets }.map { ChartRow(it.walletId, it.asset, it.amount.raw, it.amount.decimals) }
        val past = transactions.map { it.walletId to it.assetId }.distinct().filter { it !in held }.mapNotNull { (walletId, assetId) ->
            val asset = assets[assetId] ?: return@mapNotNull null
            if (asset.priceId == null || assetId in hiddenAssets) return@mapNotNull null
            val shown = assetId in visibleAssets || isVisibleWithoutBalance(asset, prices)
            if (shown) ChartRow(walletId, asset, BigInteger.ZERO, asset.decimals) else null
        }
        return rows + past
    }

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

    /**
     * Net deposits up to each sample: incoming transfers add their value at that time, outgoing
     * ones subtract it (fees excluded: they are a cost, not a withdrawal). Transfers between the
     * user's own wallets cancel out. Only charted assets are counted.
     */
    private fun cumulativeFlows(
        transactions: List<WalletTransaction>,
        rows: List<ChartRow>,
        history: Map<String, List<TimedPrice>>,
        samples: List<kotlin.time.Instant>,
    ): DoubleArray {
        // Exactly the (wallet, asset) pairs that are charted, so values and flows stay consistent.
        val assets = rows.associate { (it.walletId to it.asset.id) to it.asset }
        val events = transactions.filter { it.confirmed && it.direction != com.altaran.watchwallet.core.model.TxDirection.SELF }.mapNotNull { tx ->
            val asset = assets[tx.walletId to tx.assetId] ?: return@mapNotNull null
            val prices = history[asset.priceId ?: return@mapNotNull null].orEmpty()
            val price = Timeline.priceAt(prices, tx.timestamp) ?: return@mapNotNull null
            val scale = 10.0.pow(asset.decimals)
            val fee = tx.fee?.takeIf { tx.delta.isNegative }?.doubleValue(exactRequired = false) ?: 0.0
            tx.timestamp to (tx.delta.doubleValue(exactRequired = false) + fee) / scale * price
        }.sortedBy { it.first }
        val result = DoubleArray(samples.size)
        var running = 0.0
        var index = 0
        samples.forEachIndexed { i, sample ->
            while (index < events.size && events[index].first <= sample) {
                running += events[index].second
                index++
            }
            result[i] = running
        }
        return result
    }

    /** Balance and value history of one holding (by [Holding.key]). */
    fun holdingSeries(currency: QuoteCurrency, holdingKey: String, range: ChartRange): Flow<HoldingSeries> {
        val inputs = chartInputs(currency, null)
        return inputs.map { input ->
            input.rows.filter { keyOf(it.asset) == holdingKey }.mapNotNull { it.asset.priceId }.distinct().sorted()
        }.distinctUntilChanged().flatMapLatest { priceIds ->
            combine(
                inputs,
                priceRepository.history(priceIds, currency).onStart {
                    emit(emptyMap())
                },
            ) { input, history ->
                val rows = input.rows.filter { keyOf(it.asset) == holdingKey }
                val txs = input.transactions
                val histories = buildHistories(rows, txs, history, input.prices)
                val assetIds = rows.map { it.asset.id }.toSet()
                val (samples, rangeStart) = displaySamples(
                    range,
                    txs.filter { it.assetId in assetIds }.minOfOrNull { it.timestamp },
                )
                // Balances of each asset once (not once per sample), then summed per sample.
                val perAsset = histories.map { Timeline.balances(it, samples) }
                val balanceSeries = samples.indices.map { i -> TimedValue(samples[i], perAsset.sumOf { it[i] }) }
                HoldingSeries(
                    balance = displayDownsample(balanceSeries, rangeStart),
                    value = displayDownsample(Timeline.portfolio(histories, samples), rangeStart),
                    price = Timeline.downsample(
                        priceIds.firstOrNull()?.let { id ->
                            history[id].orEmpty().filter { it.time >= samples.first() }.map { TimedValue(it.time, it.price) }
                        }.orEmpty(),
                    ),
                )
            }.conflate()
        }.conflate().flowOn(Dispatchers.Default)
    }

    /**
     * Fetches history needed to draw [range] for [priceIds] (everything visible), then for assets
     * that are charted although no longer held (they still shape past values).
     */
    suspend fun ensureHistory(currency: QuoteCurrency, range: ChartRange, priceIds: Collection<String>) {
        val past = chartInputs(currency, null).first().priceIds.filter { it !in priceIds }
        priceRepository.ensureHistory(priceIds.toList() + past, currency, range)
    }

    private fun buildHistories(
        rows: List<ChartRow>,
        transactions: List<WalletTransaction>,
        history: Map<String, List<TimedPrice>>,
        prices: Map<String, CurrentPrice>,
    ): List<AssetHistory> {
        val txByWalletAsset = transactions.groupBy { it.walletId to it.assetId }
        return rows.map { row ->
            AssetHistory(
                currentBalance = row.balance,
                decimals = row.decimals,
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

        /**
         * Visibility of an asset no longer held anywhere (no balance row carries the user's choice
         * or spam flag): native coins, and tokens with a current price that do not look like spam.
         */
        fun isVisibleWithoutBalance(asset: Asset, prices: Map<String, CurrentPrice>): Boolean = when {
            asset.id == AssetId.native(asset.chainId) -> true
            com.altaran.watchwallet.chain.evm.SpamFilter.isSpam(asset.symbol, asset.name) -> false
            else -> asset.priceId?.let { prices.containsKey(it) } == true
        }
    }
}

/** One charted (wallet, asset) pair; [balance] is zero for assets that are no longer held. */
internal data class ChartRow(val walletId: Long, val asset: Asset, val balance: BigInteger, val decimals: Int)

internal class ChartInputs(
    val rows: List<ChartRow>,
    /** Transactions of the wallets in scope. */
    val transactions: List<WalletTransaction>,
    val prices: Map<String, CurrentPrice>,
) {
    val priceIds: List<String> = rows.mapNotNull { it.asset.priceId }.distinct().sorted()
}

data class HoldingSeries(
    val balance: List<TimedValue>,
    val value: List<TimedValue>,
    val price: List<TimedValue>,
)

/** Exposed for UI formatting of raw amounts. */
fun BigInteger.isNegative(): Boolean = this < BigInteger.ZERO
