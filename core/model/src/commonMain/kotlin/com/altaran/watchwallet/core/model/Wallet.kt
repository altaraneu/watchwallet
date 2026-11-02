package com.altaran.watchwallet.core.model

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.time.Instant

/** Bitcoin-style script types; also used for Litecoin, Dogecoin and Bitcoin Cash. */
enum class ScriptType {
    /** Legacy P2PKH (`1…`), BIP44. */
    P2PKH,

    /** Nested SegWit P2SH-P2WPKH (`3…`), BIP49. */
    P2SH_P2WPKH,

    /** Native SegWit P2WPKH (`bc1q…`), BIP84. */
    P2WPKH,

    /** Taproot P2TR (`bc1p…`), BIP86. */
    P2TR,

    /** Pay to script hash of an unknown script (`3…` single address). */
    P2SH,

    /** Native SegWit script hash (`bc1q…` 62 chars). */
    P2WSH,
}

/** What exactly is being watched. Never key material. */
sealed interface WatchTarget {
    val family: ChainFamily

    /** A single address on one chain family (EVM addresses cover all EVM networks). */
    data class Address(
        override val family: ChainFamily,
        val chainId: ChainId,
        val address: String,
        val scriptType: ScriptType? = null,
    ) : WatchTarget

    /** An extended public key; addresses are derived on the device. */
    data class ExtendedPublicKey(
        override val family: ChainFamily,
        val chainId: ChainId,
        /** The key as entered (xpub/ypub/zpub…), normalized to its standard prefix for storage. */
        val key: String,
        val scriptType: ScriptType,
        val gapLimit: Int = DEFAULT_GAP_LIMIT,
    ) : WatchTarget

    companion object {
        const val DEFAULT_GAP_LIMIT = 20
    }
}

/**
 * A tracked, watch-only wallet: a public address, an extended public key or a resolved name.
 * Never holds key material.
 */
data class Wallet(
    val id: Long,
    val name: String,
    val emoji: String?,
    val groupId: Long?,
    val notes: String?,
    val sortOrder: Int,
    val archived: Boolean,
    val createdAt: Instant,
    val target: WatchTarget?,
    /** Networks with activity (EVM: chain ids the address is used on). */
    val activeChains: List<ChainId> = emptyList(),
    val lastSyncedAt: Instant? = null,
    /** This address is subtracted from that wallet, which also contains it. */
    val subtractFromWalletId: Long? = null,
    /** Older transactions are missing, so balances can be older than the known history. */
    val historyTruncated: Boolean = false,
)

data class WalletGroup(
    val id: Long,
    val name: String,
    val sortOrder: Int,
)

/** Current balance of one asset in one wallet. */
data class AssetBalance(
    val walletId: Long,
    val asset: Asset,
    val amount: TokenAmount,
)

enum class TxDirection { IN, OUT, SELF }

/** One transaction from a wallet's point of view, with its net effect per asset. */
data class WalletTransaction(
    val walletId: Long,
    val chainId: ChainId,
    val hash: String,
    val timestamp: Instant,
    val assetId: AssetId,
    /** Net change of the wallet's balance of [assetId] (negative = outgoing, fee included). */
    val delta: BigInteger,
    val fee: BigInteger?,
    val direction: TxDirection,
    val confirmed: Boolean,
)

/** A derived address of an extended public key wallet. */
data class DerivedAddress(
    val address: String,
    val change: Boolean,
    val index: Int,
    val used: Boolean,
)
