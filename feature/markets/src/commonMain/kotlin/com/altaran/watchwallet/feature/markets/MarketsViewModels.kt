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
import kotlin.time.Clock

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

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true)
            val currency = settingsRepository.settings.first().quoteCurrency
            val holdings = portfolioRepository.snapshot(currency).first().holdings
            val held = holdings.mapNotNull { it.priceId }.filterNot { it.startsWith("token:") }
            val ids = (held + priceRepository.trackedPriceIds().filterNot { it.startsWith("token:") }).distinct()
            val result = priceRepository.markets(currency, ids)
            val rows = result.getOrNull().orEmpty().sortedBy { row -> held.indexOf(row.priceId).let { if (it < 0) Int.MAX_VALUE else it } }
            _state.value = MarketsUiState(
                loading = false,
                refreshing = false,
                failed = result.isFailure,
                currency = currency,
                rows = rows.ifEmpty { _state.value.rows },
                heldIds = held.toSet(),
            )
        }
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
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class AssetDetailViewModel(
    private val key: String,
    settingsRepository: SettingsRepository,
    private val priceRepository: PriceRepository,
    private val portfolioRepository: PortfolioRepository,
    private val marketData: MarketDataRepository,
) : ViewModel() {
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

    val state: StateFlow<AssetDetailUiState> = combine(
        combine(currency, holding, market) { c, h, mk -> Triple(c, h, mk) },
        combine(mode, range, interval, logScale) { m, r, i, l -> Settings(m, r, i, l) },
        candles,
        chart,
    ) { (c, h, mk), st, cs, points ->
        AssetDetailUiState(c, h, mk, if (h == null) AssetChartMode.PRICE else st.mode, st.range, st.interval, st.log, cs, points)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetDetailUiState())

    init {
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

    fun toggleLogScale() {
        logScale.value = !logScale.value
    }

    private data class Quad(val c: QuoteCurrency, val r: ChartRange, val m: AssetChartMode, val held: Boolean)

    private data class Settings(val mode: AssetChartMode, val range: ChartRange, val interval: CandleInterval, val log: Boolean)
}
