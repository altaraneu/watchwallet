package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.TxDirection
import com.altaran.watchwallet.core.model.WalletTransaction
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class WalletOverlapsTest {
    private val chain = ChainId("bitcoin")
    private val asset = Asset(AssetId("bitcoin:native"), chain, "BTC", "Bitcoin", 8, "bitcoin", null)
    private val xpub = 1L
    private val address = 2L
    private val links = mapOf(address to xpub)

    private fun balance(wallet: Long, raw: Long) = BalanceRow(wallet, asset, TokenAmount(BigInteger(raw), 8), false, null)

    private fun tx(wallet: Long, hash: String, delta: Long, fee: Long? = null) = WalletTransaction(
        walletId = wallet,
        chainId = chain,
        hash = hash,
        timestamp = Instant.fromEpochSeconds(1),
        assetId = asset.id,
        delta = BigInteger(delta),
        fee = fee?.let { BigInteger(it) },
        direction = if (delta < 0) TxDirection.OUT else TxDirection.IN,
        confirmed = true,
    )

    @Test
    fun balanceCountsOnceAcrossBothWallets() {
        val result = WalletOverlaps.balances(listOf(balance(xpub, 1000), balance(address, 300)), links)
        assertEquals(BigInteger(700), result.single { it.walletId == xpub }.amount.raw)
        assertEquals(BigInteger(300), result.single { it.walletId == address }.amount.raw)
    }

    @Test
    fun walletHoldingOnlyTheAddressDisappears() {
        val result = WalletOverlaps.balances(listOf(balance(xpub, 300), balance(address, 300)), links)
        assertEquals(listOf(address), result.map { it.walletId })
    }

    @Test
    fun paymentToTheAddressShowsOnlyOnTheAddressWallet() {
        val result = WalletOverlaps.transactions(listOf(tx(xpub, "a", 500), tx(address, "a", 500)), links)
        assertEquals(listOf(address), result.map { it.walletId })
    }

    @Test
    fun spendWithChangeSplitsTheTransaction() {
        // 1000 left the address: 700 paid out, 290 change to another address of the key, 10 fee.
        val result = WalletOverlaps.transactions(listOf(tx(xpub, "a", -710, fee = 10), tx(address, "a", -1000, fee = 10)), links)
        val rest = result.single { it.walletId == xpub }
        assertEquals(BigInteger(290), rest.delta)
        assertEquals(TxDirection.IN, rest.direction)
        assertEquals(null, rest.fee)
    }

    @Test
    fun otherTransactionsAndWalletsWithoutLinkStayUntouched() {
        val rows = listOf(tx(xpub, "other", 5), tx(3L, "a", 5))
        assertEquals(rows, WalletOverlaps.transactions(rows + tx(address, "a", 5), links).filter { it.walletId != address })
        assertEquals(rows, WalletOverlaps.transactions(rows, emptyMap()))
    }
}
