package com.altaran.watchwallet.core.model

import kotlinx.serialization.Serializable

/** Groups chains that share address formats and adapter logic. */
enum class ChainFamily {
    UTXO,
    EVM,
    SOLANA,
    XRP,
    TRON,
    TON,
    CARDANO,
    COSMOS,
    POLKADOT,
    STELLAR,
    SUI,
    APTOS,
    NEAR,
    HEDERA,
    ALGORAND,
    TEZOS,
    KASPA,
}

/**
 * Stable chain identifier, persisted in the database: `bitcoin`, `litecoin`, `evm:1`, `evm:8453`…
 */
@Serializable
@kotlin.jvm.JvmInline
value class ChainId(val value: String) {
    override fun toString(): String = value

    companion object {
        val BITCOIN = ChainId("bitcoin")

        fun evm(chainId: Long) = ChainId("evm:$chainId")
    }
}

/**
 * Stable asset identifier: `<chainId>:native` for the native coin, `<chainId>:<contract>` for
 * tokens (contract in lowercase).
 */
@Serializable
@kotlin.jvm.JvmInline
value class AssetId(val value: String) {
    val chainId: ChainId get() = ChainId(value.substringBeforeLast(':'))

    override fun toString(): String = value

    companion object {
        fun native(chain: ChainId) = AssetId("${chain.value}:native")

        fun token(chain: ChainId, contract: String) = AssetId("${chain.value}:${contract.lowercase()}")
    }
}

/** Static description of a chain, shown in the UI and used for explorer links. */
data class Chain(
    val id: ChainId,
    val family: ChainFamily,
    val name: String,
    val nativeSymbol: String,
    val nativeDecimals: Int,
    /** CoinGecko coin id of the native asset, for prices. */
    val nativePriceId: String?,
    /** Explorer URL templates with `{tx}` / `{address}` placeholders. */
    val explorerTxUrl: String,
    val explorerAddressUrl: String,
    /** Accent color of the chain badge (ARGB). */
    val color: Long,
)

/** A coin or token held in a wallet. */
data class Asset(
    val id: AssetId,
    val chainId: ChainId,
    val symbol: String,
    val name: String,
    val decimals: Int,
    /** CoinGecko coin id, when known. */
    val priceId: String?,
    val contract: String? = null,
)
