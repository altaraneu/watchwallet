package com.altaran.watchwallet.chain.bitcoincash

import com.altaran.watchwallet.chain.common.Base58Checks
import com.altaran.watchwallet.chain.common.BlockbookBackend
import com.altaran.watchwallet.chain.common.CashAddrCodec
import com.altaran.watchwallet.chain.common.UtxoAdapter
import com.altaran.watchwallet.chain.common.derivePublicKeyHash
import com.altaran.watchwallet.chain.common.parseExtendedPublicKey
import com.altaran.watchwallet.core.endpoints.BundledEndpoints
import com.altaran.watchwallet.core.endpoints.EndpointProtocol
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

object BitcoinCashChain {
    val ID = ChainId("bitcoincash")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.UTXO,
        name = "Bitcoin Cash",
        nativeSymbol = "BCH",
        nativeDecimals = 8,
        nativePriceId = "bitcoin-cash",
        explorerTxUrl = "https://blockchair.com/bitcoin-cash/transaction/{tx}",
        explorerAddressUrl = "https://blockchair.com/bitcoin-cash/address/{address}",
        color = 0xFF0AC18E,
    )

    val BCH = Asset(
        id = AssetId.native(ID),
        chainId = ID,
        symbol = "BCH",
        name = "Bitcoin Cash",
        decimals = 8,
        priceId = "bitcoin-cash",
    )
}

/**
 * Bitcoin Cash addresses: CashAddr (`bitcoincash:q…`/`p…`, with or without prefix, also the
 * CashTokens-aware `z…`/`r…` forms) and legacy Base58 (`1…`/`3…`, identical to Bitcoin's format).
 * Everything is normalized to prefixed CashAddr, which is what Blockbook reports.
 */
object BitcoinCashAddresses {
    const val PREFIX = "bitcoincash"
    private const val TYPE_P2PKH = 0
    private const val TYPE_P2SH = 1
    private const val TYPE_TOKEN_P2PKH = 2
    private const val TYPE_TOKEN_P2SH = 3

    private val KEY_VERSIONS: Map<Int, ScriptType?> = mapOf(0x0488B21E to ScriptType.P2PKH)

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        parseCashAddr(text)?.let { return it }
        // Legacy format: only when it is clearly not CashAddr.
        val payload = Base58Checks.decode(text) ?: return null
        if (payload.size != 21) return null
        val hash = payload.copyOfRange(1, 21)
        return when (payload[0].toInt()) {
            0x00 -> address(TYPE_P2PKH, hash)
            0x05 -> address(TYPE_P2SH, hash)
            else -> null
        }
    }

    /** `true` when the input is written in CashAddr form (unambiguously Bitcoin Cash). */
    fun isCashAddr(input: String): Boolean = parseCashAddr(input.trim()) != null

    private fun parseCashAddr(text: String): WatchTarget.Address? {
        if (':' in text && !text.lowercase().startsWith("$PREFIX:")) return null
        val (prefix, payload) = CashAddrCodec.decode(text, PREFIX) ?: return null
        if (prefix != PREFIX || payload.size != 21) return null
        val version = payload[0].toInt() and 0xFF
        if (version and 0x07 != 0) return null // size code 0 = 160-bit hash
        val hash = payload.copyOfRange(1, 21)
        return when (version ushr 3) {
            TYPE_P2PKH, TYPE_TOKEN_P2PKH -> address(TYPE_P2PKH, hash)
            TYPE_P2SH, TYPE_TOKEN_P2SH -> address(TYPE_P2SH, hash)
            else -> null
        }
    }

    private fun address(type: Int, hash: ByteArray) = WatchTarget.Address(
        family = ChainFamily.UTXO,
        chainId = BitcoinCashChain.ID,
        address = encode(type, hash),
        scriptType = if (type == TYPE_P2PKH) ScriptType.P2PKH else ScriptType.P2SH,
    )

    private fun encode(type: Int, hash: ByteArray) = CashAddrCodec.encode(
        PREFIX,
        byteArrayOf((type shl 3).toByte()) + hash,
    )

    /** Canonical prefixed CashAddr, or the input unchanged when it is not a BCH address. */
    fun normalize(address: String): String = parse(address)?.address ?: address

    /** BIP44 coin 145 accounts are exported as plain `xpub`; P2PKH only. */
    fun parseExtendedKey(input: String): WatchTarget.ExtendedPublicKey? {
        val (key, _) = parseExtendedPublicKey(input.trim(), KEY_VERSIONS) ?: return null
        return WatchTarget.ExtendedPublicKey(
            ChainFamily.UTXO,
            BitcoinCashChain.ID,
            key.encode(DeterministicWallet.xpub),
            ScriptType.P2PKH,
        )
    }

    fun derive(key: String, change: Boolean, index: Int): String {
        val (account, _) = requireNotNull(parseExtendedPublicKey(key, KEY_VERSIONS)) { "Invalid extended public key" }
        return encode(TYPE_P2PKH, derivePublicKeyHash(account, change, index))
    }
}

/** Bitcoin Cash via Blockbook (address endpoint only; extended keys are expanded on the device). */
class BitcoinCashAdapter(
    http: HttpClient,
    blockbookUrl: String = BundledEndpoints.first(BitcoinCashChain.ID, EndpointProtocol.BLOCKBOOK),
) : UtxoAdapter(
        chain = BitcoinCashChain.CHAIN,
        asset = BitcoinCashChain.BCH,
        backend = BlockbookBackend(
            http,
            blockbookUrl,
            Throttle(maxConcurrent = 3, minInterval = 150.milliseconds, maxJitter = 150.milliseconds),
            normalize = BitcoinCashAddresses::normalize,
        ),
        deriver = { target -> { change, index -> BitcoinCashAddresses.derive(target.key, change, index) } },
        normalize = BitcoinCashAddresses::normalize,
    )
