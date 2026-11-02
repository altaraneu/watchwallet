package com.altaran.watchwallet.chain.evm

import com.altaran.watchwallet.core.endpoints.BundledEndpoints
import com.altaran.watchwallet.core.endpoints.EndpointCatalog
import com.altaran.watchwallet.core.endpoints.EndpointProtocol
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId

/**
 * An EVM network. Adding a network is a data change only: append an entry to [EvmNetworks.DEFAULT].
 *
 * @property blockscoutUrl Blockscout instance (open API, no key) for tokens and history; `null`
 * when the network has none, in which case only the native balance is available via RPC.
 * @property coingeckoPlatform CoinGecko asset platform id, used to price tokens by contract.
 * @property rpcFallbacks Further RPC hosts, tried in order when [rpcUrl] fails. Every host must be
 * verified with `eth_chainId` and listed in `PRIVACY.md`. Set from the endpoint catalog.
 * @property blockscoutFallbacks Further Blockscout instances of this network.
 */
data class EvmNetwork(
    val chainId: Long,
    val name: String,
    val nativeSymbol: String,
    val nativeName: String,
    val nativePriceId: String?,
    val rpcUrl: String,
    val blockscoutUrl: String?,
    val explorerUrl: String,
    val coingeckoPlatform: String?,
    val color: Long,
    val rpcFallbacks: List<String> = emptyList(),
    val blockscoutFallbacks: List<String> = emptyList(),
) {
    val id: ChainId get() = ChainId.evm(chainId)

    val rpcUrls: List<String> get() = listOf(rpcUrl) + rpcFallbacks

    /** Empty when the network has no indexer. */
    val blockscoutUrls: List<String> get() = listOfNotNull(blockscoutUrl) + blockscoutFallbacks

    val chain: Chain
        get() = Chain(
            id = id,
            family = ChainFamily.EVM,
            name = name,
            nativeSymbol = nativeSymbol,
            nativeDecimals = 18,
            nativePriceId = nativePriceId,
            explorerTxUrl = "$explorerUrl/tx/{tx}",
            explorerAddressUrl = "$explorerUrl/address/{address}",
            color = color,
        )

    val nativeAsset: Asset
        get() = Asset(AssetId.native(id), id, nativeSymbol, nativeName, 18, nativePriceId)
}

object EvmNetworks {
    /** [DEFAULT] with the servers of [endpoints], all of them: the clients fail over in order. */
    fun withEndpoints(endpoints: EndpointCatalog): List<EvmNetwork> = DEFAULT.map { it.withEndpoints(endpoints) }

    private fun EvmNetwork.withEndpoints(endpoints: EndpointCatalog): EvmNetwork {
        val rpc = endpoints.urls(id, EndpointProtocol.EVM_RPC)
        val indexers = endpoints.urls(id, EndpointProtocol.BLOCKSCOUT)
        return copy(rpcUrl = rpc.first(), rpcFallbacks = rpc.drop(1), blockscoutUrl = indexers.firstOrNull(), blockscoutFallbacks = indexers.drop(1))
    }

    private fun rpc(chainId: Long): String = BundledEndpoints.first(ChainId.evm(chainId), EndpointProtocol.EVM_RPC)

    private fun blockscout(chainId: Long): String? = BundledEndpoints.firstOrNull(ChainId.evm(chainId), EndpointProtocol.BLOCKSCOUT)

    val DEFAULT: List<EvmNetwork> = listOf(
        EvmNetwork(
            1,
            "Ethereum",
            "ETH",
            "Ether",
            "ethereum",
            rpc(1),
            blockscout(1),
            "https://etherscan.io",
            "ethereum",
            0xFF627EEA,
        ),
        EvmNetwork(
            56,
            "BNB Smart Chain",
            "BNB",
            "BNB",
            "binancecoin",
            rpc(56),
            blockscout(56),
            "https://bscscan.com",
            "binance-smart-chain",
            0xFFF0B90B,
        ),
        EvmNetwork(
            42161,
            "Arbitrum One",
            "ETH",
            "Ether",
            "ethereum",
            rpc(42161),
            blockscout(42161),
            "https://arbiscan.io",
            "arbitrum-one",
            0xFF28A0F0,
        ),
        EvmNetwork(
            10,
            "OP Mainnet",
            "ETH",
            "Ether",
            "ethereum",
            rpc(10),
            blockscout(10),
            "https://optimistic.etherscan.io",
            "optimistic-ethereum",
            0xFFFF0420,
        ),
        EvmNetwork(
            8453,
            "Base",
            "ETH",
            "Ether",
            "ethereum",
            rpc(8453),
            blockscout(8453),
            "https://basescan.org",
            "base",
            0xFF0052FF,
        ),
        EvmNetwork(
            137,
            "Polygon PoS",
            "POL",
            "Polygon Ecosystem Token",
            "polygon-ecosystem-token",
            rpc(137),
            blockscout(137),
            "https://polygonscan.com",
            "polygon-pos",
            0xFF8247E5,
        ),
        EvmNetwork(
            43114,
            "Avalanche C-Chain",
            "AVAX",
            "Avalanche",
            "avalanche-2",
            rpc(43114),
            blockscout(43114),
            "https://snowtrace.io",
            "avalanche",
            0xFFE84142,
        ),
        EvmNetwork(
            324,
            "zkSync Era",
            "ETH",
            "Ether",
            "ethereum",
            rpc(324),
            blockscout(324),
            "https://explorer.zksync.io",
            "zksync",
            0xFF8C8DFC,
        ),
        EvmNetwork(59144, "Linea", "ETH", "Ether", "ethereum", rpc(59144), blockscout(59144), "https://lineascan.build", "linea", 0xFF61DFFF),
        EvmNetwork(
            534352,
            "Scroll",
            "ETH",
            "Ether",
            "ethereum",
            rpc(534352),
            blockscout(534352),
            "https://scrollscan.com",
            "scroll",
            0xFFEBC28E,
        ),
        EvmNetwork(
            5000,
            "Mantle",
            "MNT",
            "Mantle",
            "mantle",
            rpc(5000),
            blockscout(5000),
            "https://explorer.mantle.xyz",
            "mantle",
            0xFF65B3AE,
        ),
        EvmNetwork(
            100,
            "Gnosis",
            "XDAI",
            "xDAI",
            "xdai",
            rpc(100),
            blockscout(100),
            "https://gnosisscan.io",
            "xdai",
            0xFF04795B,
        ),
        EvmNetwork(25, "Cronos", "CRO", "Cronos", "crypto-com-chain", rpc(25), blockscout(25), "https://cronoscan.com", "cronos", 0xFF1199FA),
        EvmNetwork(146, "Sonic", "S", "Sonic", "sonic-3", rpc(146), blockscout(146), "https://sonicscan.org", "sonic", 0xFFFE9A4C),
        EvmNetwork(42220, "Celo", "CELO", "Celo", "celo", rpc(42220), blockscout(42220), "https://celoscan.io", "celo", 0xFFFCCF52),
        EvmNetwork(
            61,
            "Ethereum Classic",
            "ETC",
            "Ethereum Classic",
            "ethereum-classic",
            rpc(61),
            blockscout(61),
            "https://etc.blockscout.com",
            "ethereum-classic",
            0xFF3AB83A,
        ),
        EvmNetwork(
            999,
            "HyperEVM",
            "HYPE",
            "Hyperliquid",
            "hyperliquid",
            rpc(999),
            blockscout(999),
            "https://hyperevmscan.io",
            "hyperevm",
            0xFF97FCE4,
        ),
        EvmNetwork(
            80094,
            "Berachain",
            "BERA",
            "Berachain",
            "berachain-bera",
            rpc(80094),
            blockscout(80094),
            "https://berascan.com",
            "berachain",
            0xFF814625,
        ),
        EvmNetwork(1329, "Sei EVM", "SEI", "Sei", "sei-network", rpc(1329), blockscout(1329), "https://seitrace.com", "sei-v2", 0xFF9E1F19),
        EvmNetwork(
            130,
            "Unichain",
            "ETH",
            "Ether",
            "ethereum",
            rpc(130),
            blockscout(130),
            "https://uniscan.xyz",
            "unichain",
            0xFFF50DB4,
        ),
        EvmNetwork(81457, "Blast", "ETH", "Ether", "ethereum", rpc(81457), blockscout(81457), "https://blastscan.io", "blast", 0xFFDCDC00),
    ).map { it.withEndpoints(EndpointCatalog.BUNDLED) }
}
