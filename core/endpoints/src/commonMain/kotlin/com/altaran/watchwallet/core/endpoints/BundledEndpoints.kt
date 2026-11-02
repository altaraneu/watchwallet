package com.altaran.watchwallet.core.endpoints

import com.altaran.watchwallet.core.model.ChainId

/**
 * API types the app can talk to. A new id means new request code, so it always needs a new build:
 * the remote server list can only point an id that exists here at another host.
 */
object EndpointProtocol {
    const val ESPLORA = "esplora"
    const val BLOCKBOOK = "blockbook"
    const val EVM_RPC = "evm-rpc"
    const val BLOCKSCOUT = "blockscout"
    const val SOLANA_RPC = "solana-rpc"
    const val XRPL = "xrpl"
    const val TRONGRID = "trongrid"
    const val TONCENTER = "toncenter"
    const val KOIOS = "koios"
    const val COSMOS_REST = "cosmos-rest"
    const val SUBSTRATE_RPC = "substrate-rpc"
    const val SUBSTRATE_ASSET_HUB_RPC = "substrate-asset-hub-rpc"
    const val HORIZON = "horizon"
    const val SUI_GRAPHQL = "sui-graphql"
    const val APTOS_REST = "aptos-rest"
    const val NEAR_RPC = "near-rpc"
    const val HEDERA_MIRROR = "hedera-mirror"
    const val ALGORAND_INDEXER = "algorand-indexer"
    const val TZKT = "tzkt"
    const val KASPA_REST = "kaspa-rest"
}

/** Servers of one API type on one chain, in order of preference. */
data class EndpointEntry(val chain: String, val protocol: String, val urls: List<String>)

/**
 * The single source of truth for server addresses: every chain adapter gets its defaults from here,
 * they work offline, and the signed remote file (see [EndpointFileVerifier]) only overlays them.
 *
 * Every host has to be listed in `PRIVACY.md`, and `config/endpoints.source.json` has to carry the
 * same entries (a test keeps them equal).
 */
object BundledEndpoints {
    /** The Cosmos adapter uses one REST gateway for all its chains, so it has one shared entry. */
    val COSMOS: ChainId = ChainId("cosmos")

    val ENTRIES: List<EndpointEntry> = listOf(
        EndpointEntry("bitcoin", EndpointProtocol.ESPLORA, listOf("https://mempool.space/api", "https://blockstream.info/api")),
        EndpointEntry("litecoin", EndpointProtocol.ESPLORA, listOf("https://litecoinspace.org/api")),
        EndpointEntry("dogecoin", EndpointProtocol.BLOCKBOOK, listOf("https://dogecoin.atomicwallet.io")),
        EndpointEntry("bitcoincash", EndpointProtocol.BLOCKBOOK, listOf("https://blockbook.bch.zelcore.io")),
        EndpointEntry("solana", EndpointProtocol.SOLANA_RPC, listOf("https://api.mainnet-beta.solana.com")),
        EndpointEntry("xrp", EndpointProtocol.XRPL, listOf("https://xrplcluster.com", "https://s1.ripple.com:51234")),
        EndpointEntry("tron", EndpointProtocol.TRONGRID, listOf("https://api.trongrid.io")),
        EndpointEntry("ton", EndpointProtocol.TONCENTER, listOf("https://toncenter.com/api/v3")),
        EndpointEntry("cardano", EndpointProtocol.KOIOS, listOf("https://api.koios.rest/api/v1")),
        EndpointEntry("cosmos", EndpointProtocol.COSMOS_REST, listOf("https://rest.cosmos.directory")),
        EndpointEntry("polkadot", EndpointProtocol.SUBSTRATE_RPC, listOf("https://rpc.polkadot.io")),
        EndpointEntry("polkadot", EndpointProtocol.SUBSTRATE_ASSET_HUB_RPC, listOf("https://polkadot-asset-hub-rpc.polkadot.io")),
        EndpointEntry("kusama", EndpointProtocol.SUBSTRATE_RPC, listOf("https://kusama-rpc.polkadot.io")),
        EndpointEntry("kusama", EndpointProtocol.SUBSTRATE_ASSET_HUB_RPC, listOf("https://kusama-asset-hub-rpc.polkadot.io")),
        EndpointEntry("stellar", EndpointProtocol.HORIZON, listOf("https://horizon.stellar.org")),
        EndpointEntry("sui", EndpointProtocol.SUI_GRAPHQL, listOf("https://graphql.mainnet.sui.io/graphql")),
        EndpointEntry("aptos", EndpointProtocol.APTOS_REST, listOf("https://api.mainnet.aptoslabs.com/v1")),
        EndpointEntry("near", EndpointProtocol.NEAR_RPC, listOf("https://rpc.mainnet.near.org")),
        EndpointEntry("hedera", EndpointProtocol.HEDERA_MIRROR, listOf("https://mainnet-public.mirrornode.hedera.com/api/v1")),
        EndpointEntry("algorand", EndpointProtocol.ALGORAND_INDEXER, listOf("https://mainnet-idx.algonode.cloud")),
        EndpointEntry("tezos", EndpointProtocol.TZKT, listOf("https://api.tzkt.io/v1")),
        EndpointEntry("kaspa", EndpointProtocol.KASPA_REST, listOf("https://api.kaspa.org")),
        EndpointEntry("evm:1", EndpointProtocol.EVM_RPC, listOf("https://ethereum-rpc.publicnode.com")),
        EndpointEntry("evm:1", EndpointProtocol.BLOCKSCOUT, listOf("https://eth.blockscout.com")),
        EndpointEntry("evm:56", EndpointProtocol.EVM_RPC, listOf("https://bsc-rpc.publicnode.com")),
        EndpointEntry("evm:42161", EndpointProtocol.EVM_RPC, listOf("https://arbitrum-one-rpc.publicnode.com")),
        EndpointEntry("evm:42161", EndpointProtocol.BLOCKSCOUT, listOf("https://arbitrum.blockscout.com")),
        EndpointEntry("evm:10", EndpointProtocol.EVM_RPC, listOf("https://optimism-rpc.publicnode.com")),
        EndpointEntry("evm:10", EndpointProtocol.BLOCKSCOUT, listOf("https://optimism.blockscout.com")),
        EndpointEntry("evm:8453", EndpointProtocol.EVM_RPC, listOf("https://base-rpc.publicnode.com")),
        EndpointEntry("evm:8453", EndpointProtocol.BLOCKSCOUT, listOf("https://base.blockscout.com")),
        EndpointEntry("evm:137", EndpointProtocol.EVM_RPC, listOf("https://polygon-bor-rpc.publicnode.com")),
        EndpointEntry("evm:137", EndpointProtocol.BLOCKSCOUT, listOf("https://polygon.blockscout.com")),
        EndpointEntry("evm:43114", EndpointProtocol.EVM_RPC, listOf("https://avalanche-c-chain-rpc.publicnode.com")),
        EndpointEntry("evm:324", EndpointProtocol.EVM_RPC, listOf("https://mainnet.era.zksync.io")),
        EndpointEntry("evm:324", EndpointProtocol.BLOCKSCOUT, listOf("https://zksync.blockscout.com")),
        EndpointEntry("evm:59144", EndpointProtocol.EVM_RPC, listOf("https://rpc.linea.build")),
        EndpointEntry("evm:534352", EndpointProtocol.EVM_RPC, listOf("https://rpc.scroll.io")),
        EndpointEntry("evm:534352", EndpointProtocol.BLOCKSCOUT, listOf("https://scroll.blockscout.com")),
        EndpointEntry("evm:5000", EndpointProtocol.EVM_RPC, listOf("https://rpc.mantle.xyz")),
        EndpointEntry("evm:5000", EndpointProtocol.BLOCKSCOUT, listOf("https://explorer.mantle.xyz")),
        EndpointEntry("evm:100", EndpointProtocol.EVM_RPC, listOf("https://rpc.gnosischain.com")),
        EndpointEntry("evm:100", EndpointProtocol.BLOCKSCOUT, listOf("https://gnosis.blockscout.com")),
        EndpointEntry("evm:25", EndpointProtocol.EVM_RPC, listOf("https://evm.cronos.org")),
        EndpointEntry("evm:146", EndpointProtocol.EVM_RPC, listOf("https://rpc.soniclabs.com")),
        EndpointEntry("evm:42220", EndpointProtocol.EVM_RPC, listOf("https://forno.celo.org")),
        EndpointEntry("evm:42220", EndpointProtocol.BLOCKSCOUT, listOf("https://celo.blockscout.com")),
        EndpointEntry("evm:61", EndpointProtocol.EVM_RPC, listOf("https://etc.etcdesktop.com")),
        EndpointEntry("evm:61", EndpointProtocol.BLOCKSCOUT, listOf("https://etc.blockscout.com")),
        EndpointEntry("evm:999", EndpointProtocol.EVM_RPC, listOf("https://rpc.hyperliquid.xyz/evm")),
        EndpointEntry("evm:80094", EndpointProtocol.EVM_RPC, listOf("https://rpc.berachain.com")),
        EndpointEntry("evm:1329", EndpointProtocol.EVM_RPC, listOf("https://evm-rpc.sei-apis.com")),
        EndpointEntry("evm:130", EndpointProtocol.EVM_RPC, listOf("https://mainnet.unichain.org")),
        EndpointEntry("evm:130", EndpointProtocol.BLOCKSCOUT, listOf("https://unichain.blockscout.com")),
        EndpointEntry("evm:81457", EndpointProtocol.EVM_RPC, listOf("https://rpc.blast.io")),
    )

    private val byKey = ENTRIES.associateBy { it.chain to it.protocol }

    fun urls(chain: ChainId, protocol: String): List<String> = byKey[chain.value to protocol]?.urls.orEmpty()

    fun firstOrNull(chain: ChainId, protocol: String): String? = urls(chain, protocol).firstOrNull()

    fun first(chain: ChainId, protocol: String): String =
        requireNotNull(firstOrNull(chain, protocol)) { "No bundled $protocol endpoint for ${chain.value}" }

    /**
     * Whether the remote file may set this pair: the pairs the app ships with, plus Blockscout on
     * any EVM network since the adapter uses it wherever one is configured. Everything else (an
     * unknown chain or API type, or an API type the chain's adapter does not speak) is ignored.
     */
    internal fun accepts(chain: String, protocol: String): Boolean =
        chain to protocol in byKey ||
            (protocol == EndpointProtocol.BLOCKSCOUT && chain to EndpointProtocol.EVM_RPC in byKey)
}
