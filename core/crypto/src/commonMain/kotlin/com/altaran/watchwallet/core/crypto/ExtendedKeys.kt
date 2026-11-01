package com.altaran.watchwallet.core.crypto

import com.altaran.watchwallet.core.model.ScriptType
import fr.acinq.bitcoin.DeterministicWallet

/** Networks whose extended public keys we understand. */
enum class ExtendedKeyNetwork { BITCOIN, BITCOIN_TESTNET, LITECOIN, DOGECOIN }

/**
 * A parsed extended public key.
 *
 * @property scriptType the script type implied by the SLIP-132 prefix, or `null` for an
 * ambiguous prefix (`xpub`, `Ltub`, `dgub`) where the wallet may use any script type.
 */
data class ParsedExtendedKey(
    val key: DeterministicWallet.ExtendedPublicKey,
    val network: ExtendedKeyNetwork,
    val scriptType: ScriptType?,
    /** Canonical `xpub…` (or `tpub…`) encoding used for storage and derivation. */
    val canonical: String,
    val isMultisig: Boolean,
)

/**
 * SLIP-132 extended public key prefixes. Only public versions are listed: private versions are
 * rejected earlier by the secret guard.
 */
object ExtendedKeys {
    private data class Version(
        val network: ExtendedKeyNetwork,
        val scriptType: ScriptType?,
        val multisig: Boolean = false,
    )

    private val VERSIONS: Map<Int, Version> = mapOf(
        0x0488B21E to Version(ExtendedKeyNetwork.BITCOIN, null), // xpub
        0x049D7CB2 to Version(ExtendedKeyNetwork.BITCOIN, ScriptType.P2SH_P2WPKH), // ypub
        0x04B24746 to Version(ExtendedKeyNetwork.BITCOIN, ScriptType.P2WPKH), // zpub
        0x0295B43F to Version(ExtendedKeyNetwork.BITCOIN, ScriptType.P2SH_P2WPKH, multisig = true), // Ypub
        0x02AA7ED3 to Version(ExtendedKeyNetwork.BITCOIN, ScriptType.P2WPKH, multisig = true), // Zpub
        0x043587CF to Version(ExtendedKeyNetwork.BITCOIN_TESTNET, null), // tpub
        0x044A5262 to Version(ExtendedKeyNetwork.BITCOIN_TESTNET, ScriptType.P2SH_P2WPKH), // upub
        0x045F1CF6 to Version(ExtendedKeyNetwork.BITCOIN_TESTNET, ScriptType.P2WPKH), // vpub
        0x019DA462 to Version(ExtendedKeyNetwork.LITECOIN, null), // Ltub
        0x01B26EF6 to Version(ExtendedKeyNetwork.LITECOIN, ScriptType.P2SH_P2WPKH), // Mtub
        0x02FACAFD to Version(ExtendedKeyNetwork.DOGECOIN, null), // dgub
    )

    private val PREFIX = Regex("""^(xpub|ypub|zpub|Ypub|Zpub|tpub|upub|vpub|Ltub|Mtub|dgub)[1-9A-HJ-NP-Za-km-z]{100,112}$""")

    fun looksLikeExtendedPublicKey(value: String): Boolean = PREFIX.matches(value)

    /** Parses and validates (checksum, point on curve) an extended public key; `null` if invalid. */
    fun parse(value: String): ParsedExtendedKey? {
        if (!looksLikeExtendedPublicKey(value)) return null
        val (prefix, key) = runCatching { DeterministicWallet.ExtendedPublicKey.decode(value) }.getOrNull() ?: return null
        val version = VERSIONS[prefix] ?: return null
        // Accessing the public key validates that the bytes are a point on secp256k1.
        runCatching { key.publicKey }.getOrNull() ?: return null
        val canonicalPrefix = if (version.network == ExtendedKeyNetwork.BITCOIN_TESTNET) {
            DeterministicWallet.tpub
        } else {
            DeterministicWallet.xpub
        }
        return ParsedExtendedKey(
            key = key,
            network = version.network,
            scriptType = version.scriptType,
            canonical = key.encode(canonicalPrefix),
            isMultisig = version.multisig,
        )
    }
}
