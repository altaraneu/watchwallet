package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.TxDirection
import com.altaran.watchwallet.core.model.Wallet
import com.altaran.watchwallet.core.model.WalletTransaction
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
}
