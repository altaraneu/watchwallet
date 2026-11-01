package com.altaran.watchwallet.core.data.chain

import com.altaran.watchwallet.chain.algorand.AlgorandAddresses
import com.altaran.watchwallet.chain.aptos.AptosAddresses
import com.altaran.watchwallet.chain.bitcoincash.BitcoinCashAddresses
import com.altaran.watchwallet.chain.cardano.CardanoAddresses
import com.altaran.watchwallet.chain.cosmos.CosmosAddresses
import com.altaran.watchwallet.chain.dogecoin.DogecoinAddresses
import com.altaran.watchwallet.chain.hedera.HederaAddresses
import com.altaran.watchwallet.chain.kaspa.KaspaAddresses
import com.altaran.watchwallet.chain.litecoin.LitecoinAddresses
import com.altaran.watchwallet.chain.near.NearAddresses
import com.altaran.watchwallet.chain.polkadot.PolkadotAddresses
import com.altaran.watchwallet.chain.solana.SolanaAddresses
import com.altaran.watchwallet.chain.stellar.StellarAddresses
import com.altaran.watchwallet.chain.sui.SuiAddresses
import com.altaran.watchwallet.chain.tezos.TezosAddresses
import com.altaran.watchwallet.chain.ton.TonAddresses
import com.altaran.watchwallet.chain.tron.TronAddresses
import com.altaran.watchwallet.chain.xrp.XrpAddresses
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.WatchTarget

/**
 * Format parsers of the chains beyond Bitcoin and EVM (which `AddressClassifier` handles).
 * Every parser validates checksums; the input has already passed the secret guard.
 */
object ChainParsers {
    private val SCHEMES = listOf("litecoin:", "dogecoin:", "solana:", "ripple:", "xrpl:", "tron:", "ton://transfer/", "ton:")

    /** All interpretations of [raw] as an address or extended key of a non-Bitcoin, non-EVM chain. */
    fun parse(raw: String): List<WatchTarget> {
        val input = strip(raw.trim())
        if (input.isEmpty()) return emptyList()
        val keys = listOfNotNull(
            input.takeIf(LitecoinAddresses::isLitecoinSpecificKey)?.let(LitecoinAddresses::parseExtendedKey),
            input.takeIf(DogecoinAddresses::isDogecoinSpecificKey)?.let(DogecoinAddresses::parseExtendedKey),
        )
        if (keys.isNotEmpty()) return keys

        val cardano = CardanoAddresses.parse(input)?.let { payment ->
            // A payment address belongs to a stake key that covers the whole wallet: offer that first.
            listOfNotNull(CardanoAddresses.stakeAddressOf(payment.address)?.let(CardanoAddresses::parse), payment).distinct()
        }.orEmpty()

        val candidates = listOfNotNull(
            LitecoinAddresses.parse(input),
            DogecoinAddresses.parse(input),
            input.takeIf(BitcoinCashAddresses::isCashAddr)?.let(BitcoinCashAddresses::parse),
            SolanaAddresses.parse(input),
            XrpAddresses.parse(input),
            TronAddresses.parse(input),
            TonAddresses.parse(input),
            CosmosAddresses.parse(input),
            PolkadotAddresses.parse(input),
            StellarAddresses.parse(input),
            AlgorandAddresses.parse(input),
            TezosAddresses.parse(input),
            HederaAddresses.parse(input),
            KaspaAddresses.parse(input),
            NearAddresses.parse(input),
        ) + cardano
        return candidates.distinct()
    }

    /** `0x` + 64 hex is ambiguous (Sui, Aptos, or a private key): parsed only after the user picks the chain. */
    fun parseConfirmed(input: String, family: ChainFamily): WatchTarget? = when (family) {
        ChainFamily.SUI -> SuiAddresses.parse(input)
        ChainFamily.APTOS -> AptosAddresses.parse(input)
        else -> null
    }

    private fun strip(input: String): String {
        val scheme = SCHEMES.firstOrNull { input.startsWith(it, ignoreCase = true) } ?: return input
        return input.substring(scheme.length).removePrefix("//").substringBefore('?')
    }
}
