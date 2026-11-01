package com.altaran.watchwallet.feature.markets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altaran.watchwallet.core.chart.Candle
import com.altaran.watchwallet.core.data.repository.CandleFeed
import com.altaran.watchwallet.core.data.repository.Holding
import com.altaran.watchwallet.core.data.repository.MarketDataRepository
import com.altaran.watchwallet.core.data.repository.PortfolioRepository
import com.altaran.watchwallet.core.data.repository.PriceRepository
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.designsystem.chart.ChartPoint
import com.altaran.watchwallet.core.model.CandleInterval
import com.altaran.watchwallet.core.model.ChartRange
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.pricing.MarketInfo
import com.altaran.watchwallet.core.pricing.OhlcCandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
        val held = holdings.mapNotNull { it.priceId }.filterNot { it.startsWith("token:") }
        val ids = (held + priceRepository.trackedPriceIds().filterNot { it.startsWith("token:") }).distinct()
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
    private val currency = settingsRepository.settings.map { it.quoteCurrency }.distinctUntilChanged()

    private val holding = currency.flatMapLatest { c -> portfolioRepository.snapshot(c).map { snap -> snap.holdings.firstOrNull { it.key == key } } }

    private val symbol = combine(holding, market) { h, m -> h?.symbol ?: m?.symbol }.filterNotNull().distinctUntilChanged()

    private val chart = combine(currency, range, mode, holding.map { it != null }.distinctUntilChanged()) { c, r, m, held -> Quad(c, r, m, held) }
        .flatMapLatest { (c, r, m, held) ->
            val series = when {
                m == AssetChartMode.PRICE || !held -> priceSeries(c, r)

                else -> portfolioRepository.holdingSeries(c, key, r).map { s ->
                    (if (m == AssetChartMode.VALUE) s.value else s.balance).map { ChartPoint(it.time.toEpochMilliseconds(), it.value) }
                }
            }
            series.map<List<ChartPoint>, List<ChartPoint>?> { it }.onStart { emit(null) }
        }

    private val watched = priceRepository.watchlist.map { list -> list.any { it.priceId == key } }

    val state: StateFlow<AssetDetailUiState> = combine(
        combine(currency, holding, market) { c, h, mk -> Triple(c, h, mk) },
        combine(mode, range, interval, logScale) { m, r, i, l -> Settings(m, r, i, l) },
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
                val activity = txs.filter { it.assetId in ids }.sortedByDescending { it.timestamp }.take(MAX_ACTIVITY).map { tx ->
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
                        chainName = chain?.name,
                        walletName = names[tx.walletId].takeIf { wallets.size > 1 },
                        costUsd = cost?.valueUsd,
                        costUserSet = cost?.userSet == true,
                        explorerUrl = chain?.explorerTxUrl?.replace("{tx}", tx.hash),
                    )
                }
                shares to activity
            }.collect { extras.value = it }
        }
        // Cost basis and USD value of the holding (profit/loss is shown in USD, like the costs).
        viewModelScope.launch {
            combine(holding, walletRepository.allTransactions, costBasis.entries, walletRepository.assets) { h, txs, costs, assets ->
                if (h == null) return@combine Triple(null, true, null)
                val ids = h.assets.map { it.id }.toSet()
                val relevant = txs.filter { it.assetId in ids }
                val result = com.altaran.watchwallet.core.data.repository.CostBasisRepository.holdingCost(relevant, costs) { tx ->
                    assets[tx.assetId]?.let { com.altaran.watchwallet.core.model.TokenAmount(tx.delta, it.decimals).toDouble() } ?: 0.0
                }
                Triple(result.costUsd.takeIf { relevant.isNotEmpty() }, result.complete, h.value)
            }.collect { (c, complete, value) ->
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
        // Exchange candles with live updates; restarts when the currency, interval or asset changes.
        viewModelScope.launch {
            combine(currency, interval, symbol) { c, i, s -> Triple(c, i, s) }.collectLatest { (c, i, s) ->
                candles.value = CandleChartState.Loading
                marketData.candles(s, c, i).collect { feed ->
                    candles.value = when (feed) {
                        is CandleFeed.History -> CandleChartState.Ready(
                            candles = feed.candles.map { it.toChart() },
                            live = null,
                            vwap = feed.candles.associate { it.time to it.vwap },
                        )

                        is CandleFeed.Live -> (candles.value as? CandleChartState.Ready)?.let { ready ->
                            ready.copy(live = feed.candle.toChart(), vwap = ready.vwap + (feed.candle.time to feed.candle.vwap))
                        } ?: candles.value

                        CandleFeed.Unavailable -> CandleChartState.Unavailable
                    }
                }
            }
        }
    }

    private fun OhlcCandle.toChart() = Candle(time, open, high, low, close)

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
    }

    fun setRange(value: ChartRange) {
        range.value = value
    }

    fun setInterval(value: CandleInterval) {
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

    private data class Quad(val c: QuoteCurrency, val r: ChartRange, val m: AssetChartMode, val held: Boolean)

    private data class Settings(val mode: AssetChartMode, val range: ChartRange, val interval: CandleInterval, val log: Boolean)
}

/** Most recent transactions shown in the asset detail. */
private const val MAX_ACTIVITY = 200
