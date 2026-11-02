package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.model.WalletTransaction
import com.altaran.watchwallet.core.pricing.CurrentPrice
import com.altaran.watchwallet.core.timeline.ChartDetail
import com.altaran.watchwallet.core.timeline.ChartDetailPlanner
import com.altaran.watchwallet.core.timeline.PriceResolution
import com.altaran.watchwallet.core.timeline.TimedPrice
import com.altaran.watchwallet.core.timeline.TimedValue
import com.altaran.watchwallet.core.timeline.Timeline
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
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

/** Something in these holdings is valued with data from CoinGecko, which then has to be credited. */
fun List<Holding>.usesCoinGecko(coinGeckoPriceIds: Set<String>): Boolean =
    any { holding -> holding.value != null && holding.priceId?.let { it in coinGeckoPriceIds } == true }

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
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PortfolioRepository(
    private val walletRepository: WalletRepository,
    private val priceRepository: PriceRepository,
    private val priceDetail: PriceDetailRepository,
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

    /** Assets with stored prices from CoinGecko, see [usesCoinGecko]. */
    val coinGeckoPriceIds: Flow<Set<String>> get() = priceRepository.coinGeckoPriceIds

    /** Wallets whose value in [walletValues] includes an asset priced by CoinGecko. */
    fun walletsUsingCoinGecko(currency: QuoteCurrency): Flow<Set<Long>> =
        combine(walletRepository.balances, priceRepository.currentPrices(currency), coinGeckoPriceIds) { balances, prices, ids ->
            balances.filter { row -> isVisible(row, prices) && row.asset.priceId?.let { it in ids } == true }
                .mapTo(HashSet()) { it.walletId }
        }

    /**
     * Total value over time for the range (all wallets or a subset), downsampled for rendering.
     * What [detail] names is refined with minute or hourly prices, which are loaded while this flow
     * is collected. The series always has bars back to the first activity, so the chart can be
     * panned anywhere; it draws from what is stored and improves as better prices arrive.
     */
    fun valueSeries(
        currency: QuoteCurrency,
        range: ChartRange,
        walletIds: Set<Long>? = null,
        detail: Flow<ChartDetail?> = flowOf(null),
    ): Flow<List<TimedValue>> {
        val inputs = chartInputs(currency, walletIds)
        // Restart only when the set of assets changes; balance and price updates (every sync and
        // minute) just trigger a recomputation, so a long computation is never cancelled forever.
        return inputs.map { it.priceIds }.distinctUntilChanged().flatMapLatest { priceIds ->
            combine(
                inputs,
                priceRepository.history(priceIds, currency).onStart { emit(emptyMap()) },
                detailPrices(currency, priceIds, inputs, detail),
                minuteTicks(),
            ) { input, history, fine, _ ->
                val histories = buildHistories(input.rows, input.transactions, history, input.prices, input.wallets)
                val first = (input.transactions.map { it.timestamp } + histories.mapNotNull { it.startsAt }).minOrNull()
                val (samples, rangeStart) = displaySamples(range, first)
                val flows = cumulativeFlows(input.transactions, input.rows, history, input.wallets, samples)
                val values = Timeline.portfolio(histories, samples).mapIndexed { i, point -> point.copy(flow = flows[i]) }
                val base = displayDownsample(values, rangeStart)
                val grid = fine?.let { refinedGrid(it.detail, samples) }.orEmpty()
                if (fine == null || grid.isEmpty()) return@combine base
                // The same rows, balance starts and deposits as the base series, only with finer prices.
                val refined = buildHistories(input.rows, input.transactions, fine.over(history), input.prices, input.wallets)
                val gridFlows = cumulativeFlows(input.transactions, input.rows, history, input.wallets, grid)
                val detailValues = Timeline.portfolio(refined, grid, lastIsLive = fine.detail.live).mapIndexed { i, point -> point.copy(flow = gridFlows[i]) }
                ChartDetailPlanner.mergePoints(base, detailValues)
            }.conflate()
        }.distinctUntilChanged()
            // Hundreds of samples over years of history: never compute on the main thread, and
            // skip intermediate states while prices stream in.
            .conflate()
            .flowOn(Dispatchers.Default)
    }

    /** The series ends at now even when no price or balance changed for a while. */
    private fun minuteTicks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(SERIES_TICK_MS)
        }
    }

    /** Refined samples of the window, never older than the first bar of the chart. */
    private fun refinedGrid(detail: ChartDetail, samples: List<kotlin.time.Instant>): List<kotlin.time.Instant> {
        val start = samples.firstOrNull() ?: return emptyList()
        return ChartDetailPlanner.grid(detail, Clock.System.now().toEpochMilliseconds()).filter { it >= start }.takeIf { it.size >= 2 }.orEmpty()
    }

    /**
     * Refined prices of the current window. Fetching runs alongside and stops with the collector;
     * a new window cancels whatever is still in flight for the old one, and the old prices stay in
     * use until those of the new window have been read.
     */
    private fun detailPrices(currency: QuoteCurrency, priceIds: List<String>, inputs: Flow<ChartInputs>, detail: Flow<ChartDetail?>): Flow<DetailPrices?> =
        detail.distinctUntilChanged().flatMapLatest { window ->
            when {
                window == null -> {
                    flowOf<DetailPrices?>(null)
                }

                window.resolution == PriceResolution.DAY -> {
                    // Daily prices are already part of the long history: only the samples change.
                    flowOf<DetailPrices?>(DetailPrices(window))
                }

                else -> {
                    channelFlow<DetailPrices?> {
                        launch { priceDetail.keepLoaded(currency, window) { detailPriceIds(inputs.first(), window) } }
                        // One update per batch of arriving assets, not one per asset: the total
                        // should not wander while half of the assets are refined.
                        priceDetail.observe(priceIds, currency, window).debounce(DETAIL_BATCH_MS).collect { send(it) }
                    }
                }
            }
        }.onStart { emit(null) }

    /** Assets that had a balance in the window, most valuable first: one request for each. */
    private fun detailPriceIds(input: ChartInputs, window: ChartDetail): List<String> {
        val edges = listOf(kotlin.time.Instant.fromEpochMilliseconds(window.from), kotlin.time.Instant.fromEpochMilliseconds(window.to))
        val deltas = input.transactions.groupBy { it.walletId to it.assetId }
        val histories = buildHistories(input.rows, input.transactions, emptyMap(), emptyMap(), input.wallets)
        val weights = HashMap<String, Double>()
        input.rows.forEachIndexed { index, row ->
            val priceId = row.asset.priceId ?: return@forEachIndexed
            val held = Timeline.balances(histories[index], edges, lastIsLive = window.live).max()
            val active = deltas[row.walletId to row.asset.id].orEmpty().any { it.timestamp >= edges[0] && it.timestamp <= edges[1] }
            if (held > 0.0 || active) weights[priceId] = (weights[priceId] ?: 0.0) + held * (input.prices[priceId]?.price ?: 0.0)
        }
        return weights.entries.sortedByDescending { it.value }.map { it.key }
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
        walletRepository.activeWallets,
    ) { balances, prices, transactions, assets, wallets ->
        val scopedTxs = transactions.filter { walletIds == null || it.walletId in walletIds }
        ChartInputs(chartRows(balances, prices, scopedTxs, assets, walletIds), scopedTxs, prices, wallets.associateBy { it.id })
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
        val fine = Timeline.alignedSamples(Timeline.sampleTimes(range, now, firstActivity), range.step.inWholeMilliseconds)
        val start = fine.firstOrNull() ?: now
        if (range == ChartRange.ALL) return fine to start
        val all = Timeline.alignedSamples(Timeline.sampleTimes(ChartRange.ALL, now, firstActivity), ChartRange.ALL.step.inWholeMilliseconds)
        val coarse = all.filter { it < start }
        return (coarse + fine) to start
    }

    /** Downsamples only the older, coarse part, so the selected range keeps its resolution. */
    private fun displayDownsample(points: List<TimedValue>, rangeStart: kotlin.time.Instant): List<TimedValue> {
        val (older, inRange) = points.partition { it.time < rangeStart }
        return Timeline.downsample(older, maxPoints = OLDER_POINTS) + Timeline.downsample(inRange)
    }

    /** Balance and value history of one holding (by [Holding.key]). */
    fun holdingSeries(currency: QuoteCurrency, holdingKey: String, range: ChartRange, detail: Flow<ChartDetail?> = flowOf(null)): Flow<HoldingSeries> {
        val inputs = chartInputs(currency, null)
        return inputs.map { input ->
            input.rows.filter { keyOf(it.asset) == holdingKey }.mapNotNull { it.asset.priceId }.distinct().sorted()
        }.distinctUntilChanged().flatMapLatest { priceIds ->
            combine(
                inputs,
                priceRepository.history(priceIds, currency).onStart {
                    emit(emptyMap())
                },
                detailPrices(currency, priceIds, inputs.map { it.only(holdingKey) }, detail),
                minuteTicks(),
            ) { input, history, fine, _ ->
                val rows = input.rows.filter { keyOf(it.asset) == holdingKey }
                val txs = input.transactions
                val histories = buildHistories(rows, txs, history, input.prices, input.wallets)
                val assetIds = rows.map { it.asset.id }.toSet()
                val (samples, rangeStart) = displaySamples(
                    range,
                    (txs.filter { it.assetId in assetIds }.map { it.timestamp } + histories.mapNotNull { it.startsAt }).minOrNull(),
                )
                // Balances of each asset once (not once per sample), then summed per sample.
                val perAsset = histories.map { Timeline.balances(it, samples) }
                val balanceSeries = samples.indices.map { i -> TimedValue(samples[i], perAsset.sumOf { it[i] }) }
                val grid = fine?.let { refinedGrid(it.detail, samples) }.orEmpty()
                val refined = if (fine != null && grid.isNotEmpty()) buildHistories(rows, txs, fine.over(history), input.prices, input.wallets) else emptyList()
                val fineBalances = refined.map { Timeline.balances(it, grid, lastIsLive = fine?.detail?.live == true) }
                HoldingSeries(
                    balance = ChartDetailPlanner.mergePoints(
                        displayDownsample(balanceSeries, rangeStart),
                        if (refined.isEmpty()) emptyList() else grid.indices.map { i -> TimedValue(grid[i], fineBalances.sumOf { it[i] }) },
                    ),
                    value = ChartDetailPlanner.mergePoints(
                        displayDownsample(Timeline.portfolio(histories, samples), rangeStart),
                        if (refined.isEmpty()) emptyList() else Timeline.portfolio(refined, grid, lastIsLive = fine?.detail?.live == true),
                    ),
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
     * Fetches history needed to draw [range] for [priceIds] (everything visible). Assets charted
     * although no longer held still shape past values, but they wait in the background.
     */
    suspend fun ensureHistory(currency: QuoteCurrency, range: ChartRange, priceIds: Collection<String>) {
        priceRepository.ensureHistoryInBackground(chartInputs(currency, null).first().priceIds.filter { it !in priceIds }, currency)
        priceRepository.ensureHistory(priceIds, currency, range)
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
        /** The value series is recomputed at least this often, so it ends at now. */
        private const val SERIES_TICK_MS = 60_000L

        /** Refined prices arriving closer together than this form one update of the chart. */
        private const val DETAIL_BATCH_MS = 200L

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
    val wallets: Map<Long, com.altaran.watchwallet.core.model.Wallet>,
) {
    val priceIds: List<String> = rows.mapNotNull { it.asset.priceId }.distinct().sorted()

    /** The same inputs restricted to one holding. */
    fun only(holdingKey: String) = ChartInputs(rows.filter { PortfolioRepository.keyOf(it.asset) == holdingKey }, transactions, prices, wallets)
}

data class HoldingSeries(
    val balance: List<TimedValue>,
    val value: List<TimedValue>,
    val price: List<TimedValue>,
)

/** Exposed for UI formatting of raw amounts. */
fun BigInteger.isNegative(): Boolean = this < BigInteger.ZERO
