package com.altaran.watchwallet.core.data.chain

import com.altaran.watchwallet.chain.algorand.AlgorandAddresses
import com.altaran.watchwallet.chain.aptos.AptosAddresses
import com.altaran.watchwallet.chain.bitcoincash.BitcoinCashAddresses
import com.altaran.watchwallet.chain.bitcoincash.BitcoinCashChain
import com.altaran.watchwallet.chain.cardano.CardanoAddresses
import com.altaran.watchwallet.chain.cosmos.CosmosAddresses
import com.altaran.watchwallet.chain.dogecoin.DogecoinAddresses
import com.altaran.watchwallet.chain.dogecoin.DogecoinChain
import com.altaran.watchwallet.chain.hedera.HederaAddresses
import com.altaran.watchwallet.chain.kaspa.KaspaAddresses
import com.altaran.watchwallet.chain.litecoin.LitecoinAddresses
import com.altaran.watchwallet.chain.litecoin.LitecoinChain
import com.altaran.watchwallet.chain.near.NearAddresses
import com.altaran.watchwallet.chain.polkadot.PolkadotAddresses
import com.altaran.watchwallet.chain.solana.SolanaAddresses
import com.altaran.watchwallet.chain.stellar.StellarAddresses
import com.altaran.watchwallet.chain.sui.SuiAddresses
import com.altaran.watchwallet.chain.tezos.TezosAddresses
import com.altaran.watchwallet.chain.ton.TonAddresses
import com.altaran.watchwallet.chain.tron.TronAddresses
import com.altaran.watchwallet.chain.xrp.XrpAddresses
import com.altaran.watchwallet.core.classifier.AddressClassifier
import com.altaran.watchwallet.core.classifier.Classification
import com.altaran.watchwallet.core.crypto.BitcoinAddresses
import com.altaran.watchwallet.core.crypto.ExtendedKeyNetwork
import com.altaran.watchwallet.core.crypto.ExtendedKeys
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget

/**
 * Format parsers of the chains beyond Bitcoin and EVM (which `AddressClassifier` handles).
 * Every parser validates checksums; the input has already passed the secret guard.
 */
object ChainParsers {
    private val SCHEMES = listOf("litecoin:", "dogecoin:", "solana:", "ripple:", "xrpl:", "tron:", "ton://transfer/", "ton:")

    /**
     * [candidate] when it is proven to be a Cardano payment address of [stakeAddress]. Stored rows
     * came from a server, so anything offered for receiving money is checked again when shown.
     */
    fun cardanoPaymentAddress(candidate: String, stakeAddress: String): String? = CardanoAddresses.ownedPaymentAddress(candidate, stakeAddress)

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
            // A payment address belongs to a stake key that covers the whole wallet (all its
            // addresses): track that instead. Addresses without a stake part stay as they are.
            listOf(CardanoAddresses.stakeAddressOf(payment.address)?.let(CardanoAddresses::parse) ?: payment)
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

    /**
     * The chain validation of manual entry, for a target built from the fields of a backup: what is
     * returned comes from a parser, so a value is never used as it stands in the file. `null` when
     * the value is not valid for the chain the file claims.
     */
    fun validated(target: WatchTarget): WatchTarget? = when (target) {
        is WatchTarget.Address -> validatedAddress(target)
        is WatchTarget.ExtendedPublicKey -> validatedKey(target)
    }

    private fun validatedAddress(target: WatchTarget.Address): WatchTarget? {
        if (target.family == ChainFamily.SUI || target.family == ChainFamily.APTOS) return parseConfirmed(target.address, target.family)
        val manual = (AddressClassifier.classify(target.address) as? Classification.Candidates)?.candidates?.map { it.target }.orEmpty()
        return (manual + parse(target.address)).filterIsInstance<WatchTarget.Address>().firstOrNull { parsed ->
            // EVM wallets are stored once under the Ethereum chain id.
            parsed.family == target.family && (parsed.family == ChainFamily.EVM || parsed.chainId == target.chainId)
        }
    }

    private fun validatedKey(target: WatchTarget.ExtendedPublicKey): WatchTarget? {
        if (target.gapLimit !in 1..MAX_IMPORTED_GAP_LIMIT) return null
        val parsed = when (target.chainId) {
            ChainId.BITCOIN -> ExtendedKeys.parse(target.key)
                ?.takeIf { it.network == ExtendedKeyNetwork.BITCOIN && !it.isMultisig && target.scriptType in BitcoinAddresses.DERIVABLE_SCRIPT_TYPES }
                ?.let { WatchTarget.ExtendedPublicKey(ChainFamily.UTXO, ChainId.BITCOIN, it.canonical, target.scriptType) }

            LitecoinChain.ID -> LitecoinAddresses.parseExtendedKey(target.key, target.scriptType)

            DogecoinChain.ID -> DogecoinAddresses.parseExtendedKey(target.key)

            BitcoinCashChain.ID -> BitcoinCashAddresses.parseExtendedKey(target.key)

            else -> null
        }
        return parsed?.takeIf { it.family == target.family }?.copy(gapLimit = target.gapLimit)
    }

    /** More than any wallet needs, and a limit on the requests one backup entry can cause. */
    private const val MAX_IMPORTED_GAP_LIMIT = 200

    private fun strip(input: String): String {
        val scheme = SCHEMES.firstOrNull { input.startsWith(it, ignoreCase = true) } ?: return input
        return input.substring(scheme.length).removePrefix("//").substringBefore('?')
    }
}
