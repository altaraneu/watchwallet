package com.altaran.watchwallet.core.data

import com.altaran.watchwallet.core.crypto.EvmAddresses

/**
 * Remote coin and token logos from the Trust Wallet assets repository (MIT), which covers native
 * coins and tokens of most chains and keeps adding new ones. Derived from the price id, so every
 * asset gets a URL without extra requests; the image loader caches them on disk. Only the
 * coin/contract is revealed to GitHub, never an address of the user.
 */
object AssetIcons {
    private const val BASE = "https://raw.githubusercontent.com/trustwallet/assets/master/blockchains"
    private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

    /** CoinGecko coin id → Trust Wallet chain folder of the native coin. */
    private val COINS = mapOf(
        "bitcoin" to "bitcoin",
        "ethereum" to "ethereum",
        "binancecoin" to "smartchain",
        "solana" to "solana",
        "ripple" to "ripple",
        "cardano" to "cardano",
        "dogecoin" to "doge",
        "litecoin" to "litecoin",
        "bitcoin-cash" to "bitcoincash",
        "tron" to "tron",
        "the-open-network" to "ton",
        "polkadot" to "polkadot",
        "kusama" to "kusama",
        "stellar" to "stellar",
        "algorand" to "algorand",
        "tezos" to "tezos",
        "cosmos" to "cosmos",
        "osmosis" to "osmosis",
        "near" to "near",
        "aptos" to "aptos",
        "sui" to "sui",
        "hedera-hashgraph" to "hedera",
        "avalanche-2" to "avalanchec",
        "polygon-ecosystem-token" to "polygon",
        "matic-network" to "polygon",
        "ethereum-classic" to "classic",
        "celo" to "celo",
        "crypto-com-chain" to "cronos",
        "fantom" to "fantom",
        "mantle" to "mantle",
        "arbitrum" to "arbitrum",
        "optimism" to "optimism",
    )

    /** CoinGecko platform id → Trust Wallet chain folder (token contracts). */
    private val PLATFORMS = mapOf(
        "ethereum" to "ethereum",
        "binance-smart-chain" to "smartchain",
        "polygon-pos" to "polygon",
        "arbitrum-one" to "arbitrum",
        "optimistic-ethereum" to "optimism",
        "base" to "base",
        "avalanche" to "avalanchec",
        "xdai" to "xdai",
        "linea" to "linea",
        "zksync" to "zksync",
        "scroll" to "scroll",
        "mantle" to "mantle",
        "celo" to "celo",
        "cronos" to "cronos",
        "sonic" to "sonic",
        "solana" to "solana",
        "tron" to "tron",
    )

    /** Logo URL for a price id (`bitcoin`, `token:ethereum:0x…`), or `null` when unknown. */
    fun url(priceId: String?): String? {
        if (priceId == null) return null
        if (!priceId.startsWith("token:")) return COINS[priceId]?.let { "$BASE/$it/info/logo.png" }
        val platform = priceId.removePrefix("token:").substringBefore(':')
        val contract = priceId.substringAfterLast(':')
        val chain = PLATFORMS[platform] ?: return null
        // EVM folders use the EIP-55 checksum spelling; other chains keep the contract as is.
        val id = if (EVM_ADDRESS.matches(contract)) EvmAddresses.toChecksum(contract) else contract
        return "$BASE/$chain/assets/$id/logo.png"
    }
}
