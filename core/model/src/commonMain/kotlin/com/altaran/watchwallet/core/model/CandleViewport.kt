package com.altaran.watchwallet.core.model

/**
 * Decides which candle width a window needs. Pure, so that the hysteresis can be tested.
 *
 * The chart keeps about [MIN_BARS]..[MAX_BARS] candles in view. Coarser and finer are not exact
 * mirrors: moving to a finer interval must land well inside the band ([FINER_LANDING]), otherwise
 * the wide steps (4H to 1D is a factor of 6) would flip back and forth at a boundary.
 */
object CandleViewport {
    const val MIN_BARS = 40
    const val MAX_BARS = 200
    const val FINER_LANDING = 160

    /** Candles within this distance of the oldest loaded one count as "at the start of the data". */
    private const val EDGE_BARS = 5

    /** A finer interval must cover a little more than the window, or the view would touch its edge. */
    private const val REACH_MARGIN = 0.9
    private const val SECONDS_PER_MINUTE = 60L

    /**
     * @param bars candles in view at [current].
     * @param spanSeconds length of the window.
     * @param windowAgeSeconds time between the start of the window and now.
     * @param depthBars how many candles of any interval the source can serve at most, or `null`
     * when it can page back through its whole history.
     * @param barsBefore loaded candles to the left of the window.
     */
    fun next(current: CandleInterval, bars: Int, spanSeconds: Long, windowAgeSeconds: Long, depthBars: Int?, barsBefore: Int): CandleInterval {
        val entries = CandleInterval.entries
        val index = entries.indexOf(current)
        if (bars > MAX_BARS) {
            var result = index
            var count = bars.toDouble()
            while (count > MAX_BARS && result < entries.lastIndex) {
                count = count * entries[result].minutes / entries[result + 1].minutes
                result++
            }
            return entries[result]
        }
        // The source has nothing older at this width, but a coarser interval reaches further back.
        if (depthBars != null && barsBefore <= EDGE_BARS && index < entries.lastIndex) return entries[index + 1]
        if (bars >= MIN_BARS) return current
        var result = index
        while (result > 0) {
            val finer = entries[result - 1]
            val seconds = finer.minutes * SECONDS_PER_MINUTE
            val count = spanSeconds / seconds
            if (count > FINER_LANDING) break
            if (depthBars != null && windowAgeSeconds > depthBars * seconds * REACH_MARGIN) break
            result--
            if (count >= MIN_BARS) break
        }
        return entries[result]
    }

    /** What to load next for the window, if anything. */
    sealed interface Fetch {
        /** Candles before [beforeSeconds] (the oldest loaded one). */
        data class Older(val beforeSeconds: Long) : Fetch

        /** Candles after [afterSeconds], closing a hole in the loaded series. */
        data class Newer(val afterSeconds: Long) : Fetch
    }

    /** Holes this many intervals long or more are real gaps between loaded pages. */
    private const val GAP_INTERVALS = 3

    /** Older candles are requested this many candles before the view runs out of them. */
    private const val MIN_MARGIN_BARS = 100

    /**
     * Next page for the visible window. A hole next to the window comes first, then older candles.
     *
     * @param times loaded candle times (UTC seconds), ascending.
     * @param ignoreGapsAt holes that could not be filled (the market really had no trades).
     */
    fun nextFetch(
        times: List<Long>,
        interval: CandleInterval,
        fromSeconds: Long,
        toSeconds: Long,
        barsBefore: Int,
        bars: Int,
        canLoadOlder: Boolean,
        ignoreGapsAt: Set<Long> = emptySet(),
    ): Fetch? {
        if (times.isEmpty()) return null
        val margin = maxOf(MIN_MARGIN_BARS, bars)
        val seconds = interval.minutes * SECONDS_PER_MINUTE
        val nearFrom = fromSeconds - margin * seconds
        val nearTo = toSeconds + margin * seconds
        for (i in 0 until times.lastIndex) {
            val isGap = times[i + 1] - times[i] >= GAP_INTERVALS * seconds
            if (isGap && times[i] !in ignoreGapsAt && times[i] <= nearTo && times[i + 1] >= nearFrom) return Fetch.Newer(times[i])
        }
        return if (canLoadOlder && barsBefore < margin) Fetch.Older(times.first()) else null
    }
}
