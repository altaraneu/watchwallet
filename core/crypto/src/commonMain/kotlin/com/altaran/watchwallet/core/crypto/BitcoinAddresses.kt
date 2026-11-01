package com.altaran.watchwallet.core.crypto

import com.altaran.watchwallet.core.model.ScriptType
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.utils.Either

/** Bitcoin mainnet address validation and derivation (BIP44/49/84/86). */
object BitcoinAddresses {
    private val MAINNET = Block.LivenetGenesisBlock.hash

    /** Returns the script type of a valid mainnet address (checksum verified), otherwise `null`. */
    fun scriptTypeOf(address: String): ScriptType? {
        val valid = Bitcoin.addressToPublicKeyScript(MAINNET, address) is Either.Right
        if (!valid) return null
        val lower = address.lowercase()
        return when {
            address.startsWith("1") -> ScriptType.P2PKH
            address.startsWith("3") -> ScriptType.P2SH
            lower.startsWith("bc1q") && lower.length == P2WPKH_LENGTH -> ScriptType.P2WPKH
            lower.startsWith("bc1q") -> ScriptType.P2WSH
            lower.startsWith("bc1p") -> ScriptType.P2TR
            else -> null
        }
    }

    /**
     * Derives the address at `<key>/<change>/<index>` (the key is the account-level key, depth 3).
     * Uses non-hardened public derivation only; no private key involved.
     */
    fun derive(
        accountKey: DeterministicWallet.ExtendedPublicKey,
        scriptType: ScriptType,
        change: Boolean,
        index: Int,
    ): String {
        val publicKey = accountKey
            .derivePublicKey(if (change) 1L else 0L)
            .derivePublicKey(index.toLong())
            .publicKey
        return when (scriptType) {
            ScriptType.P2PKH -> {
                Bitcoin.computeBIP44Address(publicKey, MAINNET)
            }

            ScriptType.P2SH_P2WPKH -> {
                Bitcoin.computeBIP49Address(publicKey, MAINNET)
            }

            ScriptType.P2WPKH -> {
                Bitcoin.computeBIP84Address(publicKey, MAINNET)
            }

            ScriptType.P2TR -> {
                Bitcoin.computeBIP86Address(publicKey, MAINNET)
            }

            ScriptType.P2SH, ScriptType.P2WSH -> {
                throw IllegalArgumentException("$scriptType cannot be derived from a single-key xpub")
            }
        }
    }

    /** Script types that can be derived from a single-signature extended public key. */
    val DERIVABLE_SCRIPT_TYPES = listOf(ScriptType.P2WPKH, ScriptType.P2TR, ScriptType.P2SH_P2WPKH, ScriptType.P2PKH)

    private const val P2WPKH_LENGTH = 42
}
