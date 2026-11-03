package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.TxDirection
import com.altaran.watchwallet.core.model.Wallet
import com.altaran.watchwallet.core.model.WalletTransaction
import com.altaran.watchwallet.core.pricing.CurrentPrice
import com.altaran.watchwallet.core.timeline.TimedPrice
import com.altaran.watchwallet.core.timeline.Timeline
import com.ionspin.kotlin.bignum.integer.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class ChartHistoriesTest {
    private val solana = ChainId("solana")
    private val sol = Asset(AssetId.native(solana), solana, "SOL", "Solana", 0, "solana")
    private val staked = Asset(AssetId.token(solana, "staked-sol"), solana, "SOL", "Solana (staked)", 0, "solana", "staked-sol")
    private val reward = Asset(AssetId.token(solana, "reward"), solana, "RWD", "Reward", 0, "solana", "reward")
    private val base = Instant.fromEpochSeconds(1_700_000_000)
    private val t0 = base
    private val t1 = base + 10.days
    private val now = base + 20.days
    private val samples = listOf(t0, t0 + 5.days, t1, t1 + 5.days, now)

    /** Price 100 until t1, then 200. */
    private val prices = mapOf("solana" to listOf(TimedPrice(t0, 100.0), TimedPrice(t1, 200.0)))

    private fun wallet(truncated: Boolean = false, createdAt: Instant = t0 - 100.days) =
        Wallet(1, "W", null, null, null, 0, false, createdAt, null, historyTruncated = truncated)

    private fun tx(hash: String, time: Instant, asset: Asset, delta: Long, fee: Long? = null, direction: TxDirection = TxDirection.IN) =
        WalletTransaction(1, solana, hash, time, asset.id, BigInteger.fromLong(delta), fee?.let(BigInteger::fromLong), direction, true)

    private fun row(asset: Asset, balance: Long) = ChartRow(1, asset, BigInteger.fromLong(balance), asset.decimals)

    private class Case(
        val name: String,
        val rows: List<ChartRow>,
        val transactions: List<WalletTransaction>,
        val wallet: Wallet,
        val balanceOverTime: List<Double>,
        val flows: List<Double>,
    )

    @Test
    @Suppress("LongMethod")
    fun whereABalanceWithoutTransactionsOfItsOwnStarts() {
        val deposit = tx("in", t0, sol, 10)
        val stake = tx("stake", t1, sol, -3, fee = 1, direction = TxDirection.SELF)
        listOf(
            // Value only from the first transaction of the chain on, and not at that very sample,
            // which would become a phantom starting value of the chart.
            Case(
                "staked balance with no stake transaction starts at the first transaction of the chain",
                listOf(row(staked, 2)),
                listOf(deposit),
                wallet(),
                listOf(0.0, 2.0, 2.0, 2.0, 2.0),
                listOf(0.0, 0.0, 0.0, 0.0, 0.0),
            ),
            Case(
                "stake move is a pair: the coins arrive when they left, without the fee",
                listOf(row(staked, 2)),
                listOf(deposit, stake),
                wallet(),
                listOf(0.0, 0.0, 0.0, 2.0, 2.0),
                listOf(0.0, 0.0, 0.0, 0.0, 0.0),
            ),
            Case(
                "stake rewards do not make the balance older than its first stake move",
                listOf(row(staked, 5)),
                listOf(deposit, stake),
                wallet(),
                listOf(0.0, 0.0, 0.0, 5.0, 5.0),
                listOf(0.0, 0.0, 0.0, 0.0, 0.0),
            ),
            Case(
                "unstaking is the opposite move",
                listOf(row(staked, 1)),
                listOf(deposit, stake, tx("back", t1 + 5.days, sol, 1, fee = 1, direction = TxDirection.SELF)),
                wallet(),
                listOf(0.0, 0.0, 0.0, 1.0, 1.0),
                listOf(0.0, 0.0, 0.0, 0.0, 0.0),
            ),
            Case(
                "truncated history: the balance may be older than everything known",
                listOf(row(staked, 2)),
                listOf(deposit),
                wallet(truncated = true),
                listOf(2.0, 2.0, 2.0, 2.0, 2.0),
                listOf(0.0, 0.0, 0.0, 0.0, 0.0),
            ),
            Case(
                "truncated history still knows stake moves",
                listOf(row(staked, 3)),
                listOf(deposit, stake),
                wallet(truncated = true),
                listOf(1.0, 1.0, 3.0, 3.0, 3.0),
                listOf(0.0, 0.0, 0.0, 0.0, 0.0),
            ),
            Case(
                "no transaction on the chain at all: seen when the wallet was added, as a deposit",
                listOf(row(reward, 2)),
                emptyList(),
                wallet(createdAt = t1),
                listOf(0.0, 0.0, 0.0, 2.0, 2.0),
                listOf(0.0, 0.0, 400.0, 400.0, 400.0),
            ),
        ).forEach { case ->
            val histories = buildHistories(case.rows, case.transactions, prices, emptyMap(), mapOf(1L to case.wallet))
            assertEquals(case.name, case.balanceOverTime, Timeline.balances(histories.single(), samples))
            assertEquals(case.name, case.flows, cumulativeFlows(case.transactions, case.rows, prices, mapOf(1L to case.wallet), samples).toList())
        }
    }

    @Test
    fun assetWithItsOwnTransactionsIsRebuiltFromThem() {
        val rows = listOf(row(sol, 7))
        val txs = listOf(tx("in", t1, sol, 7))
        val history = buildHistories(rows, txs, prices, emptyMap(), mapOf(1L to wallet())).single()
        assertEquals(null, history.startsAt)
        assertEquals(listOf(0.0, 0.0, 7.0, 7.0, 7.0), Timeline.balances(history, samples))
    }

    /** The reported bug: 1.5 SOL staked from a deposit must not be a value before the deposit. */
    @Test
    fun performanceIsValueMinusNetDeposits() {
        val rows = listOf(row(sol, 7), row(staked, 3))
        val txs = listOf(tx("in", t0, sol, 10), tx("stake", t1, sol, -3, fee = 0, direction = TxDirection.SELF))
        val wallets = mapOf(1L to wallet())
        val histories = buildHistories(rows, txs, prices, mapOf(), wallets)
        val values = Timeline.portfolio(histories, samples)
        val flows = cumulativeFlows(txs, rows, prices, wallets, samples)
        assertEquals("nothing but the deposit at the start", 1000.0, values.first().value, 0.0)
        assertEquals(1000.0, flows.last() - 0.0, 0.0)
        assertEquals("10 coins bought at 100, worth 200 now", 1000.0, values.last().value - values.first().value - (flows.last() - flows.first()), 0.0)
    }

    /** A refined window uses finer prices, but the same rows, balance starts and deposits. */
    @Test
    fun refinedWindowKeepsBalanceStartsAndDeposits() {
        val rows = listOf(row(sol, 7), row(staked, 3))
        val txs = listOf(tx("in", t0, sol, 10), tx("stake", t1, sol, -3, fee = 0, direction = TxDirection.SELF))
        val wallets = mapOf(1L to wallet())
        val fine = mapOf("solana" to listOf(TimedPrice(t0, 100.0), TimedPrice(t1 - 1.days, 150.0), TimedPrice(t1, 250.0)))
        val grid = listOf(t1 - 2.days, t1 - 1.days, t1 + 1.days, t1 + 2.days)
        val refined = buildHistories(rows, txs, fine, emptyMap(), wallets)
        assertEquals(t1, refined[1].startsAt)
        assertEquals(listOf(0.0, 0.0, 3.0, 3.0), Timeline.balances(refined[1], grid, lastIsLive = false))
        assertEquals(listOf(1000.0, 1500.0, 2500.0, 2500.0), Timeline.portfolio(refined, grid, lastIsLive = false).map { it.value })
        assertEquals(List(grid.size) { 1000.0 }, cumulativeFlows(txs, rows, prices, wallets, grid).toList())
    }

    /** A window ending before the balance began has no "now" sample that could count it. */
    @Test
    fun pastWindowEndingBeforeTheBalanceBeganIsEmpty() {
        val rows = listOf(row(staked, 3))
        val txs = listOf(tx("in", t0, sol, 10), tx("stake", t1, sol, -3, fee = 0, direction = TxDirection.SELF))
        val history = buildHistories(rows, txs, prices, emptyMap(), mapOf(1L to wallet())).single()
        val grid = listOf(t0 + 1.days, t0 + 2.days)
        assertEquals(listOf(0.0, 0.0), Timeline.balances(history, grid, lastIsLive = false))
        assertEquals(listOf(0.0, 0.0), Timeline.portfolio(listOf(history), grid, lastIsLive = false).map { it.value })
    }

    // ---- What "excluding deposits and withdrawals" is, against a recomputation from transactions alone.

    private val noPriceBefore = t1

    /** The header: change of value minus change of net deposits, between the first and the last point. */
    private fun header(
        rows: List<ChartRow>,
        txs: List<WalletTransaction>,
        history: Map<String, List<TimedPrice>>,
        grid: List<Instant>,
        wallet: Wallet = wallet(),
    ): Double {
        val wallets = mapOf(1L to wallet)
        val values = Timeline.portfolio(buildHistories(rows, txs, history, emptyMap(), wallets), grid)
        val flows = cumulativeFlows(txs, rows, history, wallets, grid)
        return values.last().value - values.first().value - (flows.last() - flows.first())
    }

    /** Sums priced transactions at the last price at or before them; everything else is skipped. */
    private fun recomputedNetFlows(txs: List<WalletTransaction>, history: Map<String, List<TimedPrice>>): Double = txs.sumOf { tx ->
        val prices = history["solana"].orEmpty().filter { it.time <= tx.timestamp }
        val price = prices.lastOrNull()?.price
        if (price == null || tx.direction == TxDirection.SELF) {
            0.0
        } else {
            (tx.delta.longValue(exactRequired = false) + if (tx.delta.isNegative) (tx.fee?.longValue(exactRequired = false) ?: 0L) else 0L) * price
        }
    }

    /** Both leave fees out of the flows, so they are a loss in both and explain no difference. */
    @Test
    fun aFeeIsALossAndCountsTheSameInBothCalculations() {
        val price = mapOf("solana" to listOf(TimedPrice(t0, 100.0)))
        val txs = listOf(tx("in", t0, sol, 10), tx("out", t1, sol, -5, fee = 1, direction = TxDirection.OUT))
        val rows = listOf(row(sol, 5))
        assertEquals("exactly the fee, at its price", -100.0, header(rows, txs, price, samples), 0.0)
        assertEquals(-100.0, 5 * 100.0 - recomputedNetFlows(txs, price), 0.0)
    }

    /** Priced the same to the cent: a flow uses the price at the transaction, not at a sample. */
    @Test
    fun theSamplingOfTheChartDoesNotChangeTheFlows() {
        val txs = listOf(tx("in", t0 + 1.days, sol, 10), tx("out", t1 + 1.days, sol, -4, direction = TxDirection.OUT))
        val rows = listOf(row(sol, 6))
        val coarse = cumulativeFlows(txs, rows, prices, mapOf(1L to wallet()), listOf(t0, now)).last()
        val fine = cumulativeFlows(txs, rows, prices, mapOf(1L to wallet()), samples).last()
        assertEquals(fine, coarse, 0.0)
        assertEquals(recomputedNetFlows(txs, prices), fine, 0.0)
    }

    /**
     * A transaction older than the first known price: the app takes the first price, as it does for
     * the value at that time, so it is no phantom gain. A recomputation that skips it differs by
     * amount times first price.
     */
    @Test
    fun aTransactionOlderThanThePriceHistoryIsCountedAtTheFirstPrice() {
        val late = mapOf("solana" to listOf(TimedPrice(noPriceBefore, 100.0)))
        val txs = listOf(tx("old", t0, sol, 3))
        val flows = cumulativeFlows(txs, listOf(row(sol, 3)), late, mapOf(1L to wallet()), samples).last()
        assertEquals(300.0, flows, 0.0)
        assertEquals(0.0, recomputedNetFlows(txs, late), 0.0)
        assertEquals("no gain out of nothing", 0.0, header(listOf(row(sol, 3)), txs, late, listOf(t0, now)), 0.0)
    }

    /** A balance on a chain without any transaction is a deposit, which transactions alone cannot show. */
    @Test
    fun aBalanceWithoutTransactionsIsADepositARecomputationDoesNotSee() {
        val rows = listOf(row(reward, 2))
        val flows = cumulativeFlows(emptyList(), rows, prices, mapOf(1L to wallet(createdAt = t1)), samples).last()
        assertEquals(400.0, flows, 0.0)
        assertEquals(0.0, recomputedNetFlows(emptyList(), prices), 0.0)
    }

    /**
     * Balances are rebuilt backwards from the current one. Coins the known history does not explain
     * are in the first value of the chart, and the header compares with that. Value minus net
     * deposits does not subtract them.
     */
    @Test
    fun coinsTheHistoryDoesNotExplainAreInTheStartingValue() {
        val rows = listOf(row(sol, 12))
        val txs = listOf(tx("in", t1, sol, 10))
        val grid = listOf(t1 - 1.days, now)
        val shown = header(rows, txs, prices, grid)
        val recomputed = 12 * 200.0 - recomputedNetFlows(txs, prices)
        assertEquals("2 unexplained coins at 100 in the start", 200.0, recomputed - shown, 0.0)
    }

    /** What the rows leave out (a hidden or spam asset) is out of value and flows alike. */
    @Test
    fun anAssetThatIsNotChartedCountsForNeitherValueNorFlows() {
        val txs = listOf(tx("in", t0, sol, 10), tx("spam", t0, reward, 5))
        val rows = listOf(row(sol, 10))
        assertEquals(1000.0, cumulativeFlows(txs, rows, prices, mapOf(1L to wallet()), samples).last(), 0.0)
    }

    /** A flow without price history uses the current price, as the value does: otherwise a deposit reads as a gain. */
    @Test
    fun aDepositOfAnAssetWithoutPriceHistoryIsNoGain() {
        val current = mapOf("solana" to CurrentPrice("solana", "usd", 50.0, null))
        val txs = listOf(tx("in", t1, sol, 10))
        val rows = listOf(row(sol, 10))
        val wallets = mapOf(1L to wallet())
        val flows = cumulativeFlows(txs, rows, emptyMap(), wallets, listOf(t0, now), current)
        val values = Timeline.portfolio(buildHistories(rows, txs, emptyMap(), current, wallets), listOf(t0, now))
        assertEquals(500.0, flows.last(), 0.0)
        assertEquals("nothing but the deposit", 0.0, values.last().value - values.first().value - (flows.last() - flows.first()), 0.0)
    }

    /** All the differences at once add up exactly, so no further term is hiding in this calculation. */
    @Test
    fun theDifferenceToARecomputationIsExactlyTheSumOfItsTerms() {
        val late = mapOf("solana" to listOf(TimedPrice(t1, 100.0), TimedPrice(now, 200.0)))
        val older = 3L // before the first price: at 100 in the app, skipped by the recomputation
        val unexplained = 2L // held, but in no known transaction: part of the first value of the chart
        val txs = listOf(tx("old", t0, sol, older), tx("in", t1, sol, 10), tx("out", t1 + 5.days, sol, -2, fee = 1, direction = TxDirection.OUT))
        val balance = older + 10 - 2 + unexplained
        val rows = listOf(row(sol, balance))
        val shown = header(rows, txs, late, listOf(t0, now))
        val recomputed = balance * 200.0 - recomputedNetFlows(txs, late)
        assertEquals(1200.0, shown, 0.0)
        assertEquals(1700.0, recomputed, 0.0)
        assertEquals(older * 100.0 + unexplained * 100.0, recomputed - shown, 0.0)
    }
}
