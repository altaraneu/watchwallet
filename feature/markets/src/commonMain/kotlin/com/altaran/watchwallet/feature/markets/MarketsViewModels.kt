package com.altaran.watchwallet.feature.markets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.chart.Candle
import com.altaran.watchwallet.core.chart.ChartView
import com.altaran.watchwallet.core.common.DispatcherProvider
import com.altaran.watchwallet.core.data.repository.CandleFeed
import com.altaran.watchwallet.core.data.repository.Holding
import com.altaran.watchwallet.core.data.repository.MarketDataRepository
import com.altaran.watchwallet.core.data.repository.PortfolioRepository
import com.altaran.watchwallet.core.data.repository.PriceRepository
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.designsystem.chart.ChartPoint
import com.altaran.watchwallet.core.model.CandleInterval
import com.altaran.watchwallet.core.model.CandleViewport
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.pricing.MarketInfo
import com.altaran.watchwallet.core.pricing.OhlcCandle
import com.altaran.watchwallet.core.pricing.mergeCandles
import com.altaran.watchwallet.core.timeline.ChartViewport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

data class MarketsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val failed: Boolean = false,
    val currency: QuoteCurrency = QuoteCurrency.DEFAULT,
    /** Rows of held assets first (by value), then the watchlist. */
    val rows: List<MarketInfo> = emptyList(),
    val heldIds: Set<String> = emptySet(),
)

internal class MarketsViewModel(
    private val settingsRepository: SettingsRepository,
    private val priceRepository: PriceRepository,
    private val portfolioRepository: PortfolioRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MarketsUiState())
    val state: StateFlow<MarketsUiState> = _state

    init {
        refresh()
    }

    private val _searchResults = MutableStateFlow<List<com.altaran.watchwallet.core.pricing.CoinSearchResult>>(emptyList())
    val searchResults: StateFlow<List<com.altaran.watchwallet.core.pricing.CoinSearchResult>> = _searchResults

    fun search(query: String) {
        viewModelScope.launch { _searchResults.value = priceRepository.search(query).getOrDefault(emptyList()) }
    }

    fun addToWatchlist(coin: com.altaran.watchwallet.core.pricing.CoinSearchResult) {
        viewModelScope.launch {
            priceRepository.addToWatchlist(com.altaran.watchwallet.core.data.repository.WatchlistEntry(coin.priceId, coin.symbol, coin.name))
            _searchResults.value = emptyList()
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true)
            try {
                // A slow or rate-limited price API must not leave the screen spinning forever.
                val loaded = withTimeoutOrNull(REFRESH_TIMEOUT) { load() }
                _state.value = loaded ?: _state.value.copy(loading = false, failed = true)
            } finally {
                _state.value = _state.value.copy(refreshing = false)
            }
        }
    }

    private suspend fun load(): MarketsUiState {
        val currency = settingsRepository.settings.first().quoteCurrency
        val holdings = portfolioRepository.snapshot(currency).first().holdings
        // Known tokens (SHIB on Ethereum…) are listed as their coin; unknown tokens have no market row.
        val coinOf = com.altaran.watchwallet.core.pricing.KnownAssets::coinIdOf
        val held = holdings.mapNotNull { h -> h.priceId?.let(coinOf) }.distinct()
        val ids = (held + priceRepository.trackedPriceIds().mapNotNull(coinOf)).distinct()
        val result = priceRepository.markets(currency, ids)
        val rows = result.getOrNull().orEmpty().sortedBy { row -> held.indexOf(row.priceId).let { if (it < 0) Int.MAX_VALUE else it } }
        return MarketsUiState(
            loading = false,
            failed = result.isFailure,
            currency = currency,
            rows = rows.ifEmpty { _state.value.rows },
            heldIds = held.toSet(),
        )
    }

    private companion object {
        val REFRESH_TIMEOUT = 20.seconds
    }
}

enum class AssetChartMode { PRICE, VALUE, BALANCE }

/** Exchange candles for the price chart, or [Unavailable] when only the line chart can be shown. */
sealed interface CandleChartState {
    data object Loading : CandleChartState

    data class Ready(
        val candles: List<Candle>,
        val live: Candle?,
        /** Candle start (UTC seconds) → VWAP, for the tap details. */
        val vwap: Map<Long, Double> = emptyMap(),
        /** Interval of [candles]: the selected one may still be loading. */
        val interval: CandleInterval = CandleInterval.H1,
        /** Candles the source can serve at most, or `null` when it can page back further. */
        val depthBars: Int? = null,
    ) : CandleChartState

    data object Unavailable : CandleChartState
}

data class AssetDetailUiState(
    val currency: QuoteCurrency = QuoteCurrency.DEFAULT,
    val holding: Holding? = null,
    val market: MarketInfo? = null,
    val mode: AssetChartMode = AssetChartMode.PRICE,
    val range: ChartRange = ChartRange.M1,
    val interval: CandleInterval = CandleInterval.H1,
    /** Changes when a chip or the currency resets the view of the candle chart; zooming does not. */
    val candleViewKey: Int = 0,
    val logScale: Boolean = false,
    val candles: CandleChartState = CandleChartState.Loading,
    /** Line chart (fallback price, value or balance). */
    val chart: List<ChartPoint>? = null,
    /** `null` when the asset cannot be watched (unpriced token). */
    val watched: Boolean? = null,
    /** Cost basis of the holding in USD (average cost), and whether every transfer has one. */
    val costUsd: Double? = null,
    val costComplete: Boolean = true,
    /** Current value of the holding in USD (for the profit/loss). */
    val valueUsd: Double? = null,
    /** Where the holding is: one row per wallet. */
    val wallets: List<WalletShare> = emptyList(),
    /** Transactions of this asset across all wallets, newest first. */
    val activity: List<com.altaran.watchwallet.core.ui.TxView> = emptyList(),
)

/** Amount of an asset held in one wallet. */
data class WalletShare(val walletId: Long, val name: String, val amount: Double, val value: Double?)

@OptIn(ExperimentalCoroutinesApi::class)
internal class AssetDetailViewModel(
    private val key: String,
    private val settingsRepository: SettingsRepository,
    private val priceRepository: PriceRepository,
    private val portfolioRepository: PortfolioRepository,
    private val marketData: MarketDataRepository,
    private val walletRepository: com.altaran.watchwallet.core.data.repository.WalletRepository,
    private val costBasis: com.altaran.watchwallet.core.data.repository.CostBasisRepository,
    private val fx: com.altaran.watchwallet.core.pricing.FxRates,
    private val registry: com.altaran.watchwallet.core.data.chain.ChainRegistry,
    /** Filtering and sorting of all transactions and price series runs off the main thread. */
    private val dispatchers: DispatcherProvider = DispatcherProvider(),
) : ViewModel() {
    private val cost = MutableStateFlow<Triple<Double?, Boolean, Double?>>(Triple(null, true, null))
    private val extras = MutableStateFlow<Pair<List<WalletShare>, List<com.altaran.watchwallet.core.ui.TxView>>>(emptyList<WalletShare>() to emptyList())

    fun setCost(tx: com.altaran.watchwallet.core.ui.TxView, valueUsd: Double?) {
        viewModelScope.launch {
            costBasis.setUserValue(com.altaran.watchwallet.core.data.repository.CostKey(tx.walletId, tx.hash, tx.assetId), valueUsd)
        }
    }
    private val mode = MutableStateFlow(AssetChartMode.PRICE)
    private val range = MutableStateFlow(ChartRange.M1)
    private val interval = MutableStateFlow(CandleInterval.H1)
    private val logScale = MutableStateFlow(false)
    private val market = MutableStateFlow<MarketInfo?>(null)
    private val candles = MutableStateFlow<CandleChartState>(CandleChartState.Loading)
    private val candleViewKey = MutableStateFlow(0)

    /** The settled view of the candle chart; drives paging. */
    private val candleView = MutableStateFlow<ChartView?>(null)

    /** The view to keep across an interval change caused by zooming. */
    private var zoomedWindow: ChartView? = null

    /** The part of the value chart on screen, refined with better prices. */
    private val viewport = ChartViewport(range.value)
    private val currency = settingsRepository.settings.map { it.quoteCurrency }.distinctUntilChanged()

    private val holding = currency.flatMapLatest { c -> portfolioRepository.snapshot(c).map { snap -> snap.holdings.firstOrNull { it.key == key } } }

    private val symbol = combine(holding, market) { h, m -> h?.symbol ?: m?.symbol }.filterNotNull().distinctUntilChanged()

    // A new range keeps the old series until the new one is computed: the chart never goes blank.
    private val chart = combine(currency, mode, holding.map { it != null }.distinctUntilChanged()) { c, m, held -> Triple(c, m, held) }
        .flatMapLatest { (c, m, held) ->
            range.flatMapLatest { r ->
                when {
                    m == AssetChartMode.PRICE || !held -> priceSeries(c, r)

                    else -> portfolioRepository.holdingSeries(c, key, r, viewport.detail).map { s ->
                        (if (m == AssetChartMode.VALUE) s.value else s.balance).map { ChartPoint(it.time.toEpochMilliseconds(), it.value) }
                    }
                }
            }.map<List<ChartPoint>, List<ChartPoint>?> { it }.onStart { emit(null) }
        }
        .flowOn(dispatchers.default)

    private val watched = priceRepository.watchlist.map { list -> list.any { it.priceId == key } }

    val state: StateFlow<AssetDetailUiState> = combine(
        combine(currency, holding, market) { c, h, mk -> Triple(c, h, mk) },
        combine(mode, range, interval, logScale, candleViewKey) { m, r, i, l, k -> Settings(m, r, i, l, k) },
        combine(candles, watched) { cs, w -> cs to w },
        chart,
    ) { (c, h, mk), st, (cs, w), points ->
        AssetDetailUiState(
            c,
            h,
            mk,
            if (h == null) AssetChartMode.PRICE else st.mode,
            st.range,
            st.interval,
            st.viewKey,
            st.log,
            cs,
            points,
            watched = if (key.startsWith("token:") || key.contains(':')) null else w,
        )
    }.combine(cost) { s, (c, complete, usd) -> s.copy(costUsd = c, costComplete = complete, valueUsd = usd) }
        .combine(extras) { s, (w, a) -> s.copy(wallets = w, activity = a) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetDetailUiState())

    init {
        viewModelScope.launch { costBasis.fillAutomatic() }
        // Per-wallet amounts and the asset's activity across wallets (one place for everything).
        viewModelScope.launch {
            combine(
                holding,
                combine(walletRepository.allTransactions, costBasis.entries) { t, c -> t to c },
                walletRepository.assets,
                walletRepository.activeWallets,
                walletRepository.balances,
            ) { h, (txs, costs), assets, wallets, balances ->
                if (h == null) return@combine emptyList<WalletShare>() to emptyList()
                val ids = h.assets.map { it.id }.toSet()
                val names = wallets.associate { it.id to it.name }
                val shares = balances.filter { it.asset.id in ids }.groupBy { it.walletId }.map { (walletId, rows) ->
                    val amount = rows.sumOf { it.amount.toDouble() }
                    WalletShare(walletId, names[walletId] ?: "?", amount, h.price?.let { it * amount })
                }.sortedByDescending { it.amount }
                val relevantTxs = txs.filter { it.assetId in ids }
                val manyChains = relevantTxs.map { it.chainId }.distinct().size > 1
                val manyWallets = relevantTxs.map { it.walletId }.distinct().size > 1
                val activity = relevantTxs.sortedByDescending { it.timestamp }.take(MAX_ACTIVITY).map { tx ->
                    val asset = assets[tx.assetId]
                    val chain = registry.chain(tx.chainId)
                    val cost = costs[com.altaran.watchwallet.core.data.repository.CostKey(tx.walletId, tx.hash, tx.assetId.value)]
                    com.altaran.watchwallet.core.ui.TxView(
                        walletId = tx.walletId,
                        hash = tx.hash,
                        assetId = tx.assetId.value,
                        direction = tx.direction,
                        confirmed = tx.confirmed,
                        time = tx.timestamp,
                        amount = asset?.let { com.altaran.watchwallet.core.model.TokenAmount(tx.delta, it.decimals).toDouble() } ?: 0.0,
                        symbol = asset?.symbol ?: h.symbol,
                        chainName = chain?.name?.takeIf { manyChains },
                        walletName = names[tx.walletId].takeIf { manyWallets },
                        costUsd = cost?.valueUsd,
                        costUserSet = cost?.userSet == true,
                        explorerUrl = chain?.explorerTxUrl?.replace("{tx}", tx.hash),
                    )
                }
                shares to activity
            }.flowOn(dispatchers.default).collect { extras.value = it }
        }
        // Cost basis and USD value of the holding (profit/loss is shown in USD, like the costs).
        viewModelScope.launch {
            combine(holding, walletRepository.allTransactions, costBasis.entries, walletRepository.assets) { h, txs, costs, assets ->
                if (h == null) return@combine Triple(null, true, null)
                val ids = h.assets.map { it.id }.toSet()
                val relevant = txs.filter { it.assetId in ids }
                val result = com.altaran.watchwallet.core.data.repository.CostBasisRepository.holdingCost(relevant, costs, heldAmount = h.amount) { tx ->
                    assets[tx.assetId]?.let { com.altaran.watchwallet.core.model.TokenAmount(tx.delta, it.decimals).toDouble() } ?: 0.0
                }
                Triple(result.costUsd.takeIf { relevant.isNotEmpty() }, result.complete, h.value)
            }.flowOn(dispatchers.default).collect { (c, complete, value) ->
                val code = settingsRepository.settings.first().quoteCurrency
                val usd = when {
                    value == null -> null
                    code == com.altaran.watchwallet.core.model.QuoteCurrency.USD -> value
                    code.kind == com.altaran.watchwallet.core.model.QuoteCurrency.Kind.FIAT -> runCatching { value / fx.rate("USD", code.code) }.getOrNull()
                    else -> null
                }
                cost.value = Triple(c, complete, usd)
            }
        }
        viewModelScope.launch {
            combine(currency, range) { c, r -> c to r }.collect { (c, r) ->
                if (!key.startsWith("token:") && !key.contains(":native")) {
                    market.value = priceRepository.markets(c, listOf(key)).getOrNull()?.firstOrNull()
                }
                priceRepository.ensureHistory(listOf(key), c, r)
            }
        }
        // Exchange candles with live updates; restarts when the currency, interval or asset changes,
        // which also closes the previous live connection. An interval change caused by zooming
        // keeps the old candles on screen until the new ones are there.
        viewModelScope.launch {
            combine(currency, interval, symbol) { c, i, s -> Triple(c, i, s) }.collectLatest { (c, i, s) ->
                val zoomed = zoomedWindow
                zoomedWindow = null
                if (zoomed == null) candles.value = CandleChartState.Loading
                candleView.value = null
                candleSession(s, c, i, zoomed)
            }
        }
    }

    /** One interval of one asset: the feed plus older pages requested by the view. */
    private suspend fun candleSession(symbol: String, currency: QuoteCurrency, interval: CandleInterval, zoomed: ChartView?) = coroutineScope {
        val session = CandleSession(symbol, currency, interval)
        launch { candleView.filterNotNull().collectLatest { view -> loadPageFor(session, view) } }
        var anchor = zoomed
        marketData.candles(symbol, currency, interval).collect { feed ->
            when (feed) {
                is CandleFeed.History -> {
                    session.depthBars = feed.depthBars
                    session.merge(feed.candles, anchor?.from ?: candleView.value?.from ?: Long.MAX_VALUE)
                    // Zoomed while panned back: the latest page may not reach the window.
                    anchor?.let { loadAround(session, it) }
                    anchor = null
                    publish(session)
                }

                is CandleFeed.Live -> {
                    session.merge(listOf(feed.candle), candleView.value?.from ?: Long.MAX_VALUE)
                    candles.value = (candles.value as? CandleChartState.Ready)?.let { ready ->
                        if (ready.interval != interval) return@let ready
                        ready.copy(live = feed.candle.toChart(), vwap = ready.vwap + (feed.candle.time to feed.candle.vwap))
                    } ?: candles.value
                }

                CandleFeed.Unavailable -> {
                    candles.value = CandleChartState.Unavailable
                }
            }
        }
    }

    private suspend fun loadAround(session: CandleSession, window: ChartView) {
        if (session.depthBars != null || session.candles.firstOrNull()?.let { it.time <= window.from } == true) return
        val seconds = session.interval.minutes * SECONDS_PER_MINUTE
        val page = runCatchingPage {
            marketData.candlePage(
                session.symbol,
                session.currency,
                session.interval,
                beforeSeconds =
                    window.to + MAX_VISIBLE_BARS * seconds,
            )
        }
        session.merge(page, window.from)
    }

    /** Requests what the settled [view] needs next; the page changes the view report, and so on. */
    private suspend fun loadPageFor(session: CandleSession, view: ChartView) {
        if ((candles.value as? CandleChartState.Ready)?.interval != session.interval) return
        val fetch = CandleViewport.nextFetch(
            times = session.candles.map { it.time },
            interval = session.interval,
            fromSeconds = view.from,
            toSeconds = view.to,
            barsBefore = view.barsBefore,
            bars = view.bars,
            canLoadOlder = session.depthBars == null && !session.reachedStart,
            ignoreGapsAt = session.emptyGaps,
        ) ?: return
        when (fetch) {
            is CandleViewport.Fetch.Older -> {
                val page = runCatchingPage { marketData.candlePage(session.symbol, session.currency, session.interval, beforeSeconds = fetch.beforeSeconds) }
                if (page.isEmpty()) session.reachedStart = true else session.merge(page, view.from)
            }

            is CandleViewport.Fetch.Newer -> {
                val page = runCatchingPage { marketData.candlePage(session.symbol, session.currency, session.interval, afterSeconds = fetch.afterSeconds) }
                val next = session.candles.firstOrNull { it.time > fetch.afterSeconds }?.time
                if (page.none { next == null || it.time < next }) session.emptyGaps += fetch.afterSeconds
                session.merge(page, view.from)
            }
        }
        publish(session)
    }

    /** A failed page leaves the chart as it is; the next settled view asks again. */
    private suspend fun runCatchingPage(block: suspend () -> List<OhlcCandle>): List<OhlcCandle> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
    ) {
        emptyList()
    }

    private suspend fun publish(session: CandleSession) {
        val list = session.candles
        val live = (candles.value as? CandleChartState.Ready)?.takeIf { it.interval == session.interval }?.live
        candles.value = withContext(dispatchers.default) {
            CandleChartState.Ready(
                candles = list.map { it.toChart() },
                live = live,
                vwap = list.associate { it.time to it.vwap },
                interval = session.interval,
                depthBars = session.depthBars,
            )
        }
    }

    /** The candle chart settled on [view]: follows the zoom with the candle width, or loads pages. */
    fun onCandleView(view: ChartView) {
        val ready = candles.value as? CandleChartState.Ready ?: return
        if (ready.interval != interval.value) return
        if (view.userMoved) {
            val next = CandleViewport.next(
                current = ready.interval,
                bars = view.bars,
                spanSeconds = view.to - view.from,
                windowAgeSeconds = Clock.System.now().epochSeconds - view.from,
                depthBars = ready.depthBars,
                barsBefore = view.barsBefore,
            )
            if (next != ready.interval) {
                zoomedWindow = view
                interval.value = next
                return
            }
        }
        candleView.value = view
    }

    private fun OhlcCandle.toChart() = Candle(time, open, high, low, close)

    /** The candles of one interval; only touched on the main thread. */
    private class CandleSession(val symbol: String, val currency: QuoteCurrency, val interval: CandleInterval) {
        var candles: List<OhlcCandle> = emptyList()
        var depthBars: Int? = null
        var reachedStart = false
        val emptyGaps = HashSet<Long>()

        fun merge(new: List<OhlcCandle>, keepAround: Long) {
            candles = mergeCandles(candles, new, MAX_CANDLES_IN_MEMORY, keepAround)
        }
    }

    private fun priceSeries(currency: QuoteCurrency, range: ChartRange) = priceRepository.history(listOf(key), currency).map { all ->
        val points = all[key].orEmpty()
        val start = range.span?.let { Clock.System.now() - it }
        val inRange = if (start == null) points else points.filter { it.time >= start }
        com.altaran.watchwallet.core.timeline.Timeline.downsample(
            inRange.map { com.altaran.watchwallet.core.timeline.TimedValue(it.time, it.price) },
        ).map { ChartPoint(it.time.toEpochMilliseconds(), it.value) }
    }

    fun setMode(value: AssetChartMode) {
        mode.value = value
        // The line chart of prices has no minutes to show.
        if (value == AssetChartMode.PRICE && range.value == ChartRange.H1) setRange(ChartRange.H24)
    }

    fun setRange(value: ChartRange) {
        viewport.show(value)
        range.value = value
    }

    /** The value chart settled on a window after pan or zoom (epoch ms). */
    fun onVisibleWindow(from: Long, to: Long) {
        viewport.onVisible(from, to)
    }

    /** A tap on an interval chip: that interval, in its default view. */
    fun setInterval(value: CandleInterval) {
        zoomedWindow = null
        candleViewKey.value++
        interval.value = value
    }

    fun toggleWatch(currentlyWatched: Boolean) {
        val info = market.value
        viewModelScope.launch {
            val entry = com.altaran.watchwallet.core.data.repository.WatchlistEntry(key, info?.symbol ?: key.uppercase(), info?.name ?: key)
            if (currentlyWatched) priceRepository.removeFromWatchlist(entry) else priceRepository.addToWatchlist(entry)
        }
    }

    fun toggleLogScale() {
        logScale.value = !logScale.value
    }

    private data class Settings(val mode: AssetChartMode, val range: ChartRange, val interval: CandleInterval, val log: Boolean, val viewKey: Int)
}

/** Candles of one interval kept while paging back: about 3.5 days of minutes, 7 months of hours. */
private const val MAX_CANDLES_IN_MEMORY = 5000
private const val MAX_VISIBLE_BARS = 200
private const val SECONDS_PER_MINUTE = 60L

/** Most recent transactions shown in the asset detail. */
private const val MAX_ACTIVITY = 200
