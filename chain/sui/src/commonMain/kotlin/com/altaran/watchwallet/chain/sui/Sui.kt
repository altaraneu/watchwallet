package com.altaran.watchwallet.chain.sui

import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget

object SuiChain {
    val ID = ChainId("sui")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.SUI,
        name = "Sui",
        nativeSymbol = "SUI",
        nativeDecimals = 9,
        nativePriceId = "sui",
        explorerTxUrl = "https://suiscan.xyz/mainnet/tx/{tx}",
        explorerAddressUrl = "https://suiscan.xyz/mainnet/account/{address}",
        color = 0xFF4DA2FF,
    )

    val SUI: Asset = nativeAsset(CHAIN)

    const val DEFAULT_GRAPHQL_URL = "https://graphql.mainnet.sui.io/graphql"
    const val SUI_COIN_TYPE = "0x2::sui::SUI"
    const val COINGECKO_PLATFORM = "sui"
}

/**
 * Sui addresses: `0x` + 64 hex characters (32 bytes). The format has no checksum, so only the
 * shape can be validated. The same shape is used by Aptos; the classifier must ask the user.
 */
object SuiAddresses {
    private val SHAPE = Regex("^0[xX][0-9a-fA-F]{64}$")

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        if (!SHAPE.matches(text)) return null
        return WatchTarget.Address(ChainFamily.SUI, SuiChain.ID, "0x" + text.substring(2).lowercase())
    }
}
