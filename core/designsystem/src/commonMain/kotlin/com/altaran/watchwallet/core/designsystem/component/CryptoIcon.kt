package com.altaran.watchwallet.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaran.watchwallet.core.designsystem.generated.resources.Res
import com.altaran.watchwallet.core.designsystem.generated.resources.allDrawableResources
import org.jetbrains.compose.resources.painterResource

/** Maps asset symbols and chain ids to bundled icon keys (`chain_<key>.png`). */
object CryptoIcons {
    private val CHAIN_KEYS = mapOf(
        "bitcoin" to "btc",
        "litecoin" to "ltc",
        "dogecoin" to "doge",
        "bitcoincash" to "bch",
        "evm:1" to "eth",
        "evm:56" to "bnb",
        "evm:42161" to "arbitrum",
        "evm:10" to "optimism",
        "evm:8453" to "base",
        "evm:137" to "polygon",
        "evm:43114" to "avax",
        "evm:324" to "zksync",
        "evm:59144" to "linea",
        "evm:534352" to "scroll",
        "evm:5000" to "mantle",
        "evm:100" to "gno",
        "evm:25" to "cronos",
        "evm:146" to "sonic",
        "evm:42220" to "celo",
        "evm:61" to "etc",
        "evm:999" to "hyperevm",
        "evm:1329" to "sei",
        "evm:81457" to "blast",
        "solana" to "sol",
        "xrp" to "xrp",
        "tron" to "trx",
        "ton" to "ton",
        "cardano" to "ada",
        "stellar" to "xlm",
        "algorand" to "algo",
        "tezos" to "xtz",
        "near" to "near",
        "hedera" to "hedera",
        "aptos" to "aptos",
        "polkadot" to "dot",
        "kusama" to "ksm",
        "cosmos:cosmoshub" to "atom",
        "cosmos:osmosis" to "osmosis",
    )

    private val SYMBOL_KEYS = mapOf(
        "BTC" to "btc",
        "ETH" to "eth",
        "WETH" to "eth",
        "BNB" to "bnb",
        "AVAX" to "avax",
        "POL" to "polygon",
        "MATIC" to "polygon",
        "OP" to "optimism",
        "ARB" to "arbitrum",
        "MNT" to "mantle",
        "CRO" to "cronos",
        "CELO" to "celo",
        "S" to "sonic",
        "SEI" to "sei",
        "GNO" to "gno",
        "XDAI" to "gno",
        "ETC" to "etc",
        "HYPE" to "hyperevm",
        "LTC" to "ltc",
        "DOGE" to "doge",
        "BCH" to "bch",
        "SOL" to "sol",
        "XRP" to "xrp",
        "TRX" to "trx",
        "TON" to "ton",
        "ADA" to "ada",
        "ATOM" to "atom",
        "OSMO" to "osmosis",
        "DOT" to "dot",
        "KSM" to "ksm",
        "XLM" to "xlm",
        "APT" to "aptos",
        "NEAR" to "near",
        "HBAR" to "hedera",
        "ALGO" to "algo",
        "XTZ" to "xtz",
        "USDC" to "usdc",
        "USDC.E" to "usdc",
        "USDT" to "usdt",
        "USDT0" to "usdt",
        "DAI" to "dai",
    )

    fun forChain(chainId: String): String? = CHAIN_KEYS[chainId]

    fun forSymbol(symbol: String): String? = SYMBOL_KEYS[symbol.uppercase()]
}

/**
 * Round coin / network icon. Falls back to a colored circle with the first letter when no
 * bundled icon exists, so every asset still gets a recognizable mark.
 */
@Composable
fun CryptoIcon(
    iconKey: String?,
    fallbackText: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    fallbackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    val resource = iconKey?.let { Res.allDrawableResources["chain_$it"] }
    if (resource != null) {
        Image(
            painter = painterResource(resource),
            contentDescription = null,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(CircleShape).background(fallbackColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fallbackText.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
