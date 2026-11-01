package com.altaran.watchwallet.chain.dogecoin

import com.altaran.watchwallet.chain.common.Base58Checks
import com.altaran.watchwallet.chain.common.BlockbookBackend
import com.altaran.watchwallet.chain.common.UtxoAdapter
import com.altaran.watchwallet.chain.common.derivePublicKeyHash
import com.altaran.watchwallet.chain.common.parseExtendedPublicKey
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import fr.acinq.bitcoin.DeterministicWallet
import io.ktor.client.HttpClient
import kotlin.time.Duration.Companion.milliseconds

object DogecoinChain {
    val ID = ChainId("dogecoin")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.UTXO,
        name = "Dogecoin",
        nativeSymbol = "DOGE",
        nativeDecimals = 8,
        nativePriceId = "dogecoin",
        explorerTxUrl = "https://blockchair.com/dogecoin/transaction/{tx}",
        explorerAddressUrl = "https://blockchair.com/dogecoin/address/{address}",
        color = 0xFFC2A633,
    )

    val DOGE = Asset(
        id = AssetId.native(ID),
        chainId = ID,
        symbol = "DOGE",
        name = "Dogecoin",
        decimals = 8,
        priceId = "dogecoin",
    )

    const val DEFAULT_BLOCKBOOK_URL = "https://doge1.trezor.io"
}

/** Dogecoin mainnet addresses (`D…` P2PKH, `9…`/`A…` P2SH) and `dgub`/`xpub` account keys (BIP44 coin 3). */
object DogecoinAddresses {
    private const val P2PKH: Byte = 0x1E
    private const val P2SH: Byte = 0x16

    private val KEY_VERSIONS: Map<Int, ScriptType?> = mapOf(
        0x02FACAFD to ScriptType.P2PKH, // dgub
        0x0488B21E to ScriptType.P2PKH, // xpub (as exported by Trezor/Ledger for Dogecoin)
    )

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        val payload = Base58Checks.decode(text) ?: return null
        if (payload.size != 21) return null
        val type = when (payload[0]) {
            P2PKH -> ScriptType.P2PKH
            P2SH -> ScriptType.P2SH
            else -> return null
        }
        return WatchTarget.Address(ChainFamily.UTXO, DogecoinChain.ID, text, type)
    }

    /** Dogecoin has no SegWit: every extended key is BIP44 P2PKH. */
    fun parseExtendedKey(input: String): WatchTarget.ExtendedPublicKey? {
        val (key, _) = parseExtendedPublicKey(input.trim(), KEY_VERSIONS) ?: return null
        return WatchTarget.ExtendedPublicKey(
            ChainFamily.UTXO,
            DogecoinChain.ID,
            key.encode(DeterministicWallet.xpub),
            ScriptType.P2PKH,
        )
    }

    /** `true` for the Dogecoin-specific `dgub` prefix (an `xpub` may belong to any chain). */
    fun isDogecoinSpecificKey(input: String): Boolean = input.startsWith("dgub")

    fun derive(key: String, change: Boolean, index: Int): String {
        val (account, _) = requireNotNull(parseExtendedPublicKey(key, KEY_VERSIONS)) { "Invalid extended public key" }
        return Base58Checks.encode(byteArrayOf(P2PKH) + derivePublicKeyHash(account, change, index))
    }
}

/** Dogecoin via Blockbook (address endpoint only; extended keys are expanded on the device). */
class DogecoinAdapter(
    http: HttpClient,
    blockbookUrl: String = DogecoinChain.DEFAULT_BLOCKBOOK_URL,
) : UtxoAdapter(
        chain = DogecoinChain.CHAIN,
        asset = DogecoinChain.DOGE,
        backend = BlockbookBackend(
            http,
            blockbookUrl,
            Throttle(maxConcurrent = 3, minInterval = 150.milliseconds, maxJitter = 150.milliseconds),
        ),
        deriver = { target -> { change, index -> DogecoinAddresses.derive(target.key, change, index) } },
    )
