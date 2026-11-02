package com.altaran.watchwallet.core.designsystem.component

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp
import com.altaran.watchwallet.core.designsystem.generated.resources.Res
import com.altaran.watchwallet.core.designsystem.generated.resources.allDrawableResources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoIconTest {
    private val url = "https://example.invalid/logo.png"

    @Test
    fun bundledLogoNeverCausesARequest() {
        for (allowed in listOf(true, false)) {
            assertIs<IconSource.Bundled>(iconSource("btc", { true }, url, allowed))
        }
    }

    @Test
    fun otherAssetsDownloadOnlyWhenAllowed() {
        assertEquals(IconSource.Letter, iconSource(null, { true }, url, remoteAllowed = false))
        assertEquals(IconSource.Remote(url), iconSource(null, { true }, url, remoteAllowed = true))
        // A key without a drawable behaves like no key.
        assertEquals(IconSource.Letter, iconSource("nope", { false }, url, remoteAllowed = false))
        assertEquals(IconSource.Letter, iconSource(null, { true }, null, remoteAllowed = true))
    }

    @Test
    fun everyKeyHasADrawable() {
        val missing = CryptoIcons.allKeys.filter { "chain_$it" !in Res.allDrawableResources }
        assertTrue(missing.isEmpty(), "No drawable for $missing")
    }

    @Test
    fun nativeCoinsOfSupportedChainsAreBundled() {
        val native = """
            bitcoin ethereum binancecoin polygon-ecosystem-token avalanche-2 mantle xdai
            crypto-com-chain sonic-3 celo ethereum-classic hyperliquid sei-network litecoin
            dogecoin bitcoin-cash solana ripple tron the-open-network cardano cosmos osmosis
            celestia injective-protocol polkadot kusama stellar sui aptos near
            hedera-hashgraph algorand tezos
        """.trim().split(Regex("\\s+"))
        assertEquals(emptyList(), native.filter { CryptoIcons.forPriceId(it) == null })
    }

    @Test
    fun tokensMatchByContractNotBySymbol() {
        assertEquals("usdc", CryptoIcons.forPriceId("token:ethereum:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"))
        assertNull(CryptoIcons.forPriceId("token:ethereum:0x0000000000000000000000000000000000000bad"))
        assertNull(CryptoIcons.forPriceId(null))
    }

    @Test
    fun creditMeetsTheMinimumFontSizeOfTheTerms() {
        assertTrue(Typography().labelSmall.fontSize >= 10.sp)
    }
}
