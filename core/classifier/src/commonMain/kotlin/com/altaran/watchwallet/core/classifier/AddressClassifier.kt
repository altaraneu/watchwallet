package com.altaran.watchwallet.core.classifier

import com.altaran.watchwallet.core.crypto.BitcoinAddresses
import com.altaran.watchwallet.core.crypto.EvmAddresses
import com.altaran.watchwallet.core.crypto.ExtendedKeyNetwork
import com.altaran.watchwallet.core.crypto.ExtendedKeys
import com.altaran.watchwallet.core.crypto.OutputDescriptors
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.security.SecretCheck
import com.altaran.watchwallet.core.security.SecretGuard
import com.altaran.watchwallet.core.security.SecretKind

enum class Confidence { CERTAIN, LIKELY, POSSIBLE }

/** One possible interpretation of the input. */
data class Candidate(
    val target: WatchTarget,
    val confidence: Confidence,
)

sealed interface Classification {
    /** The input looks like key material. It must be discarded immediately. */
    data class Secret(val kind: SecretKind) : Classification

    /**
     * One or more interpretations. When there are several, an on-chain activity probe decides
     * which ones to keep.
     */
    data class Candidates(val candidates: List<Candidate>) : Classification

    /**
     * Valid format that also matches a private key format (`0x` + 64 hex: Sui / Aptos address
     * or an EVM private key). Never probed automatically: the user must pick the chain explicitly
     * and confirm, see [ConfirmationRequired.options].
     */
    data class ConfirmationRequired(val value: String, val options: List<ChainFamily>) : Classification

    /** Recognized format of a chain that the app does not support yet. */
    data class Unsupported(val family: ChainFamily) : Classification

    /** Mixed-case EVM address whose EIP-55 checksum does not match (likely a typo). */
    data object WrongChecksum : Classification

    /** Several addresses: switch to the batch import flow. */
    data class Multiple(val entries: List<String>) : Classification

    data object Unrecognized : Classification
}

/**
 * Pure, offline classification of pasted input. The secret guard always runs first.
 *
 * Supported now: Bitcoin addresses, extended public keys (SLIP-132) and single-key output
 * descriptors; EVM addresses. Other chains are recognized and reported as [Classification.Unsupported]
 * until their adapters exist.
 */
object AddressClassifier {
    fun classify(rawInput: String): Classification {
        val secret = SecretGuard.check(rawInput)
        if (secret is SecretCheck.Secret) return Classification.Secret(secret.kind)

        val entries = splitEntries(rawInput)
        if (entries.size > 1) return Classification.Multiple(entries)
        val input = entries.firstOrNull()?.let(PaymentUri::strip) ?: return Classification.Unrecognized

        return bitcoin(input)
            ?: evm(input)
            ?: suiOrAptos(input)
            ?: OtherChains.recognize(input)?.let { Classification.Unsupported(it) }
            ?: Classification.Unrecognized
    }

    /** Splits bulk input (one address per line, or separated by commas, semicolons or spaces). */
    internal fun splitEntries(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()
        // A descriptor contains commas and brackets but is a single entry.
        if (OutputDescriptors.looksLikeDescriptor(trimmed)) return listOf(trimmed)
        return trimmed.split(Regex("""[\s,;]+""")).filter { it.isNotBlank() }.distinct()
    }

    private fun bitcoin(input: String): Classification? {
        BitcoinAddresses.scriptTypeOf(input)?.let { scriptType ->
            val address = if (input.startsWith("bc1", ignoreCase = true)) input.lowercase() else input
            return Classification.Candidates(
                listOf(Candidate(WatchTarget.Address(ChainFamily.UTXO, ChainId.BITCOIN, address, scriptType), Confidence.CERTAIN)),
            )
        }
        OutputDescriptors.parse(input)?.let { descriptor ->
            if (descriptor.extendedKey.network != ExtendedKeyNetwork.BITCOIN) return Classification.Unrecognized
            return Classification.Candidates(listOf(xpubCandidate(descriptor.extendedKey.canonical, descriptor.scriptType, Confidence.CERTAIN)))
        }
        ExtendedKeys.parse(input)?.let { key ->
            if (key.isMultisig) return Classification.Unrecognized
            return when (key.network) {
                ExtendedKeyNetwork.BITCOIN -> {
                    val scriptType = key.scriptType
                    if (scriptType != null) {
                        Classification.Candidates(listOf(xpubCandidate(key.canonical, scriptType, Confidence.CERTAIN)))
                    } else {
                        // Bare xpub: any script type is possible; the probe shows which have history.
                        Classification.Candidates(
                            BitcoinAddresses.DERIVABLE_SCRIPT_TYPES.map { xpubCandidate(key.canonical, it, Confidence.POSSIBLE) },
                        )
                    }
                }

                ExtendedKeyNetwork.LITECOIN -> {
                    Classification.Unsupported(ChainFamily.UTXO)
                }

                ExtendedKeyNetwork.DOGECOIN -> {
                    Classification.Unsupported(ChainFamily.UTXO)
                }

                ExtendedKeyNetwork.BITCOIN_TESTNET -> {
                    Classification.Unrecognized
                }
            }
        }
        return null
    }

    private fun xpubCandidate(canonical: String, scriptType: ScriptType, confidence: Confidence) = Candidate(
        WatchTarget.ExtendedPublicKey(ChainFamily.UTXO, ChainId.BITCOIN, canonical, scriptType),
        confidence,
    )

    private fun evm(input: String): Classification? = when (val result = EvmAddresses.validate(input)) {
        is EvmAddresses.Validation.Valid -> Classification.Candidates(
            listOf(
                Candidate(
                    // EVM wallets are stored once with the Ethereum chain id; the adapter scans all networks.
                    WatchTarget.Address(ChainFamily.EVM, ChainId.evm(1), result.checksummed),
                    Confidence.CERTAIN,
                ),
            ),
        )

        EvmAddresses.Validation.WrongChecksum -> Classification.WrongChecksum

        EvmAddresses.Validation.Invalid -> null
    }

    private val HEX_64 = Regex("""^0x[0-9a-fA-F]{64}$""")

    private fun suiOrAptos(input: String): Classification? =
        if (HEX_64.matches(input)) {
            Classification.ConfirmationRequired(input.lowercase(), listOf(ChainFamily.SUI, ChainFamily.APTOS))
        } else {
            null
        }
}

/** Strips payment URI schemes and parameters: BIP21, EIP-681, `solana:`, `ripple:`, `tron:`… */
internal object PaymentUri {
    private val SCHEMES = listOf(
        "bitcoin:",
        "litecoin:",
        "dogecoin:",
        "bitcoincash:",
        "ethereum:",
        "solana:",
        "ripple:",
        "xrpl:",
        "tron:",
        "ton://transfer/",
        "ton:",
    )

    fun strip(input: String): String {
        var value = input.trim()
        val scheme = SCHEMES.firstOrNull { value.startsWith(it, ignoreCase = true) }
        if (scheme != null) {
            // Bitcoin Cash keeps its prefix in CashAddr; the scheme is part of the address.
            if (scheme != "bitcoincash:") value = value.substring(scheme.length)
            value = value.removePrefix("//")
            value = value.substringBefore('?')
            // EIP-681: ethereum:0xabc@137/transfer?…
            value = value.substringBefore('@').substringBefore('/')
        }
        return value
    }
}

/** Recognition (format only) of chains whose adapters arrive in later milestones. */
internal object OtherChains {
    private val rules: List<Pair<Regex, ChainFamily>> = listOf(
        Regex("""^(ltc1|L|M)[0-9A-Za-z]{25,90}$""") to ChainFamily.UTXO,
        Regex("""^D[1-9A-HJ-NP-Za-km-z]{33}$""") to ChainFamily.UTXO,
        Regex("""^(bitcoincash:)?[qp][02-9ac-hj-np-z]{41}$""") to ChainFamily.UTXO,
        Regex("""^T[1-9A-HJ-NP-Za-km-z]{33}$""") to ChainFamily.TRON,
        Regex("""^r[1-9A-HJ-NP-Za-km-z]{24,34}$""") to ChainFamily.XRP,
        Regex("""^X[1-9A-HJ-NP-Za-km-z]{46}$""") to ChainFamily.XRP,
        Regex("""^(EQ|UQ|kQ|0Q)[A-Za-z0-9_-]{46}$|^-?[01]:[0-9a-fA-F]{64}$""") to ChainFamily.TON,
        Regex("""^(addr1|stake1)[02-9ac-hj-np-z]{50,120}$""") to ChainFamily.CARDANO,
        Regex("""^[a-z]{2,15}1[02-9ac-hj-np-z]{38,58}$""") to ChainFamily.COSMOS,
        Regex("""^G[A-Z2-7]{55}$""") to ChainFamily.STELLAR,
        Regex("""^[A-Z2-7]{58}$""") to ChainFamily.ALGORAND,
        Regex("""^(tz1|tz2|tz3|KT1)[1-9A-HJ-NP-Za-km-z]{33}$""") to ChainFamily.TEZOS,
        Regex("""^0\.0\.\d+$""") to ChainFamily.HEDERA,
        Regex("""^[a-z0-9_-]+(\.[a-z0-9_-]+)*\.near$""") to ChainFamily.NEAR,
        Regex("""^kaspa:[02-9ac-hj-np-z]{61,63}$""") to ChainFamily.KASPA,
        Regex("""^[1-9A-HJ-NP-Za-km-z]{47,48}$""") to ChainFamily.POLKADOT,
    )

    /** Bech32 prefixes of UTXO chains: a failed checksum there must not look like a Cosmos address. */
    private val UTXO_HRPS = setOf("bc", "tb", "bcrt", "ltc", "tltc")
    private val SOLANA = Regex("""^[1-9A-HJ-NP-Za-km-z]{32,44}$""")
    private const val SOLANA_KEY_BYTES = 32

    fun recognize(input: String): ChainFamily? {
        if (input.substringBefore('1', "").lowercase() in UTXO_HRPS) return null
        rules.firstOrNull { (regex, _) -> regex.matches(input) }?.let { return it.second }
        // Solana: base58 that decodes to exactly 32 bytes (a 34-char BTC address decodes to 25).
        if (SOLANA.matches(input)) {
            val size = runCatching { fr.acinq.bitcoin.Base58.decode(input).size }.getOrDefault(-1)
            if (size == SOLANA_KEY_BYTES) return ChainFamily.SOLANA
        }
        return null
    }
}
