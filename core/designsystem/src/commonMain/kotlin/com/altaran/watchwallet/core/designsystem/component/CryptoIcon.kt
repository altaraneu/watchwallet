package com.altaran.watchwallet.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
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
        "cosmos:celestia" to "celestia",
        "cosmos:injective" to "injective",
        "sui" to "sui",
    )

    /** CoinGecko coin id → icon key: native coins of the supported chains and well-known tokens. */
    private val COIN_KEYS = mapOf(
        "bitcoin" to "btc",
        "ethereum" to "eth",
        "binancecoin" to "bnb",
        "polygon-ecosystem-token" to "polygon",
        "matic-network" to "polygon",
        "avalanche-2" to "avax",
        "mantle" to "mantle",
        "xdai" to "gno",
        "crypto-com-chain" to "cronos",
        "sonic-3" to "sonic",
        "celo" to "celo",
        "ethereum-classic" to "etc",
        "hyperliquid" to "hyperevm",
        "sei-network" to "sei",
        "litecoin" to "ltc",
        "dogecoin" to "doge",
        "bitcoin-cash" to "bch",
        "solana" to "sol",
        "ripple" to "xrp",
        "tron" to "trx",
        "the-open-network" to "ton",
        "cardano" to "ada",
        "cosmos" to "atom",
        "osmosis" to "osmosis",
        "celestia" to "celestia",
        "injective-protocol" to "injective",
        "polkadot" to "dot",
        "kusama" to "ksm",
        "stellar" to "xlm",
        "sui" to "sui",
        "aptos" to "aptos",
        "near" to "near",
        "hedera-hashgraph" to "hedera",
        "algorand" to "algo",
        "tezos" to "xtz",
        "tether" to "usdt",
        "usd-coin" to "usdc",
        "dai" to "dai",
        "weth" to "eth",
        "wrapped-bitcoin" to "wbtc",
        "shiba-inu" to "shib",
        "chiliz" to "chz",
        "ankr" to "ankr",
        "chainlink" to "link",
        "uniswap" to "uni",
        "pepe" to "pepe",
        "arbitrum" to "arbitrum",
        "optimism" to "optimism",
    )

    /**
     * Token price id → icon key. Matched by contract, never by symbol, which spam tokens copy: a
     * token merely calling itself USDC must not get the real logo. `scripts/fetch_logos.py` checks
     * this table against Trust Wallet's metadata.
     */
    private val TOKEN_KEYS = mapOf(
        "token:ethereum:0xdac17f958d2ee523a2206206994597c13d831ec7" to "usdt",
        "token:ethereum:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" to "usdc",
        "token:ethereum:0x6b175474e89094c44da98b954eedeac495271d0f" to "dai",
        "token:ethereum:0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2" to "eth",
        "token:ethereum:0x2260fac5e5542a773aa44fbcfedf7c193bc2c599" to "wbtc",
        "token:ethereum:0x95ad61b0a150d79219dcf64e1e6cc01f0b64c4ce" to "shib",
        "token:ethereum:0x3506424f91fd33084466f402d5d97f05f8e3b4af" to "chz",
        "token:ethereum:0x8290333cef9e6d528dd5618fb97a76f268f3edd4" to "ankr",
        "token:ethereum:0x514910771af9ca656af840dff83e8264ecf986ca" to "link",
        "token:ethereum:0x1f9840a85d5af5bf1d1762f925bdaddc4201f984" to "uni",
        "token:ethereum:0x455e53cbb86018ac2b8092fdcd39d8444affc3f6" to "polygon",
        "token:ethereum:0x7d1afa7b718fb893db30a3abc0cfc608aacfebb0" to "polygon",
        "token:ethereum:0x6982508145454ce325ddbe47a25d4ec3d2311933" to "pepe",
        "token:arbitrum-one:0xaf88d065e77c8cc2239327c5edb3a432268e5831" to "usdc",
        "token:arbitrum-one:0xff970a61a04b1ca14834a43f5de4533ebddb5cc8" to "usdc",
        "token:arbitrum-one:0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9" to "usdt",
        "token:arbitrum-one:0xda10009cbd5d07dd0cecc66161fc93d7c9000da1" to "dai",
        "token:arbitrum-one:0x82af49447d8a07e3bd95bd0d56f35241523fbab1" to "eth",
        "token:arbitrum-one:0x912ce59144191c1204e64559fe8253a0e49e6548" to "arbitrum",
        "token:base:0x833589fcd6edb6e08f4c7c32d4f71b54bda02913" to "usdc",
        "token:base:0xd9aaec86b65d86f6a7b5b1b0c42ffa531710b6ca" to "usdc",
        "token:base:0x4200000000000000000000000000000000000006" to "eth",
        "token:optimistic-ethereum:0x0b2c639c533813f4aa9d7837caf62653d097ff85" to "usdc",
        "token:optimistic-ethereum:0x7f5c764cbc14f9669b88837ca1490cca17c31607" to "usdc",
        "token:optimistic-ethereum:0x94b008aa00579c1307b0ef2c499ad98a8ce58e58" to "usdt",
        "token:optimistic-ethereum:0xda10009cbd5d07dd0cecc66161fc93d7c9000da1" to "dai",
        "token:optimistic-ethereum:0x4200000000000000000000000000000000000006" to "eth",
        "token:optimistic-ethereum:0x4200000000000000000000000000000000000042" to "optimism",
        "token:polygon-pos:0x3c499c542cef5e3811e1192ce70d8cc03d5c3359" to "usdc",
        "token:polygon-pos:0x2791bca1f2de4661ed88a30c99a7a9449aa84174" to "usdc",
        "token:polygon-pos:0xc2132d05d31c914a87c6611c10748aeb04b58e8f" to "usdt",
        "token:polygon-pos:0x8f3cf7ad23cd3cadbd9735aff958023239c6a063" to "dai",
        "token:polygon-pos:0x7ceb23fd6bc0add59e62ac25578270cff1b9f619" to "eth",
        "token:binance-smart-chain:0x55d398326f99059ff775485246999027b3197955" to "usdt",
        "token:binance-smart-chain:0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d" to "usdc",
        "token:binance-smart-chain:0x1af3f329e8be154074d8769d1ffa4ee058b1dbc3" to "dai",
        "token:avalanche:0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e" to "usdc",
        "token:avalanche:0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7" to "usdt",
        "token:tron:TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t" to "usdt",
        "token:solana:EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "usdc",
        "token:solana:Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to "usdt",
    )

    fun forChain(chainId: String): String? = CHAIN_KEYS[chainId]

    /** Icon key of a coin or token by its price id (`bitcoin`, `token:ethereum:0x…`), if bundled. */
    fun forPriceId(priceId: String?): String? = COIN_KEYS[priceId] ?: TOKEN_KEYS[priceId]

    /** All keys the tables can return; every one must have a drawable. */
    internal val allKeys: Set<String> get() = (CHAIN_KEYS.values + COIN_KEYS.values + TOKEN_KEYS.values).toSet()
}

/**
 * The user asked for logos that are not bundled to be downloaded (setting, provided at the app
 * root). Off by default: every download tells the host which token is on screen.
 */
val LocalRemoteIcons = compositionLocalOf { false }

/** Where the mark of an asset comes from. */
internal sealed interface IconSource {
    data class Bundled(val key: String) : IconSource

    data class Remote(val url: String) : IconSource

    data object Letter : IconSource
}

/** A bundled logo always wins, so an asset that has one never causes a request. */
internal fun iconSource(iconKey: String?, isBundled: (String) -> Boolean, remoteUrl: String?, remoteAllowed: Boolean): IconSource = when {
    iconKey != null && isBundled(iconKey) -> IconSource.Bundled(iconKey)
    remoteUrl != null && remoteAllowed -> IconSource.Remote(remoteUrl)
    else -> IconSource.Letter
}

/**
 * Round coin / network icon: the bundled logo, else the downloaded one ([remoteUrl], cached on
 * disk) when the user allowed downloads, else a colored circle with the first letter.
 */
@Composable
fun CryptoIcon(
    iconKey: String?,
    fallbackText: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    fallbackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    remoteUrl: String? = null,
) {
    val source = iconSource(iconKey, { "chain_$it" in Res.allDrawableResources }, remoteUrl, LocalRemoteIcons.current)
    if (source is IconSource.Remote) {
        SubcomposeAsyncImage(
            model = source.url,
            contentDescription = null,
            modifier = modifier.size(size).clip(CircleShape),
            loading = { LocalIcon(null, fallbackText, Modifier, size, fallbackColor) },
            error = { LocalIcon(null, fallbackText, Modifier, size, fallbackColor) },
        )
    } else {
        LocalIcon((source as? IconSource.Bundled)?.key, fallbackText, modifier, size, fallbackColor)
    }
}

@Composable
private fun LocalIcon(iconKey: String?, fallbackText: String, modifier: Modifier, size: Dp, fallbackColor: Color) {
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
