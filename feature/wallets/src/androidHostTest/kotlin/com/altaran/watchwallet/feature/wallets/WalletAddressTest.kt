// Fixtures contain long real addresses.
@file:Suppress("ktlint:standard:max-line-length")

package com.altaran.watchwallet.feature.wallets

import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.DerivedAddress
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.feature.wallets.generated.resources.Res
import com.altaran.watchwallet.feature.wallets.generated.resources.address_copy
import com.altaran.watchwallet.feature.wallets.generated.resources.address_next_receive
import com.altaran.watchwallet.feature.wallets.generated.resources.detail_address
import com.altaran.watchwallet.feature.wallets.generated.resources.detail_address_evm_hint
import com.altaran.watchwallet.feature.wallets.generated.resources.detail_stake_key
import com.altaran.watchwallet.feature.wallets.generated.resources.detail_stake_key_copy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletAddressTest {
    // CIP-19 test vectors (mainnet): the base address carries this stake key.
    private val base = "addr1qx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgse35a3x"
    private val enterprise = "addr1vx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzers66hrl8"
    private val stake = "stake1uyehkck0lajq8gr28t9uxnuvgcqrc6070x3k9r8048z8y5gh6ffgw"

    /** A valid base address with the same payment part and somebody else's stake part. */
    private val foreign = "addr1qx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzers3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygsdnys4h"

    private val stakeTarget = WatchTarget.Address(ChainFamily.CARDANO, ChainId("cardano"), stake)
    private val xpub = WatchTarget.ExtendedPublicKey(ChainFamily.UTXO, ChainId.BITCOIN, "xpub-test", ScriptType.P2WPKH, 20)

    private fun row(address: String, change: Boolean = false, index: Int = 0, used: Boolean = true) = DerivedAddress(address, change, index, used)

    private fun assertOnlyStakeKeyShown(derived: List<DerivedAddress>) {
        val shown = shownAddress(stakeTarget, derived)!!
        assertEquals(stake, shown.value)
        assertEquals(Res.string.detail_stake_key, shown.label)
        assertFalse("a stake key cannot receive, so no QR is offered", shown.canReceive)
        assertEquals("the button says what it copies", Res.string.detail_stake_key_copy, shown.copyLabel)
    }

    /** Even one carrying the wallet's stake key: its payment key may be anybody's. */
    @Test
    fun aServerAddressIsNeverOfferedEvenWithTheRightStakeKey() {
        assertOnlyStakeKeyShown(listOf(row(base)))
    }

    @Test
    fun aStoredAddressOfAnotherWalletIsNotOffered() {
        assertOnlyStakeKeyShown(listOf(row(foreign)))
        assertOnlyStakeKeyShown(listOf(row(foreign), row(base)))
    }

    @Test
    fun invalidStoredValuesAreNotOffered() {
        listOf(enterprise, stake, base.dropLast(1) + "y", "not an address", "", "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu").forEach {
            assertOnlyStakeKeyShown(listOf(row(it)))
        }
    }

    @Test
    fun withoutARowOnlyTheStakeKeyIsShown() {
        assertOnlyStakeKeyShown(emptyList())
    }

    @Test
    fun anExtendedKeyShowsItsFirstUnusedReceiveAddress() {
        val derived =
            listOf(
                row("used", index = 0),
                row("change", change = true, index = 0, used = false),
                row("next", index = 1, used = false),
                row("later", index = 2, used = false),
            )
        val shown = shownAddress(xpub, derived)!!
        assertEquals("next", shown.value)
        assertEquals(Res.string.address_next_receive, shown.label)
        assertTrue(shown.canReceive)
    }

    @Test
    fun anExtendedKeyWithoutAnUnusedAddressShowsNothing() {
        assertNull(shownAddress(xpub, listOf(row("used"), row("change", change = true, used = false))))
        assertNull(shownAddress(xpub, emptyList()))
    }

    @Test
    fun aPlainAddressShowsWhatTheUserEnteredWhateverIsStored() {
        val target = WatchTarget.Address(ChainFamily.UTXO, ChainId.BITCOIN, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")
        assertEquals(target.address, shownAddress(target, listOf(row(foreign)))!!.value)
    }

    @Test
    fun anEvmAddressCarriesTheNetworkHint() {
        val target = WatchTarget.Address(ChainFamily.EVM, ChainId.evm(1), "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045")
        assertEquals(Res.string.detail_address_evm_hint, shownAddress(target, emptyList())!!.hint)
    }

    @Test
    fun onlyACardanoWalletCountsAsAStakeWallet() {
        val target = WatchTarget.Address(ChainFamily.COSMOS, ChainId("cosmos"), "stake1something")
        assertEquals(Res.string.detail_address, shownAddress(target, emptyList())!!.label)
    }

    @Test
    fun anAddressThatCanReceiveIsCopiedAsAnAddress() {
        val shown = shownAddress(WatchTarget.Address(ChainFamily.CARDANO, ChainId("cardano"), base), emptyList())!!
        assertEquals(Res.string.address_copy, shown.copyLabel)
    }
}
