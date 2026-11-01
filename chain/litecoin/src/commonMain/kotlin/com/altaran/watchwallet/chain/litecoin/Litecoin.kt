package com.altaran.watchwallet.chain.litecoin

import com.altaran.watchwallet.chain.common.Base58Checks
import com.altaran.watchwallet.chain.common.Bech32Codec
import com.altaran.watchwallet.chain.common.UtxoAdapter
import com.altaran.watchwallet.chain.common.derivePublicKeyHash
import com.altaran.watchwallet.chain.common.p2shP2wpkhScriptHash
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

object LitecoinChain {
    val ID = ChainId("litecoin")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.UTXO,
        name = "Litecoin",
        nativeSymbol = "LTC",
        nativeDecimals = 8,
        nativePriceId = "litecoin",
        explorerTxUrl = "https://litecoinspace.org/tx/{tx}",
        explorerAddressUrl = "https://litecoinspace.org/address/{address}",
        color = 0xFF345D9D,
    )

    val LTC = Asset(
        id = AssetId.native(ID),
        chainId = ID,
        symbol = "LTC",
        name = "Litecoin",
        decimals = 8,
        priceId = "litecoin",
    )

    const val DEFAULT_ESPLORA_URL = "https://litecoinspace.org/api"
}

/**
 * Litecoin mainnet addresses (`L…`, `M…`, legacy `3…`, `ltc1…`) and extended public keys
 * (`Ltub`, `Mtub`, and the Bitcoin-style `xpub`/`ypub`/`zpub` that Electrum-LTC and hardware
 * wallets export for Litecoin accounts).
 */
object LitecoinAddresses {
    private const val P2PKH: Byte = 0x30
    private const val P2SH: Byte = 0x32
    private const val P2SH_LEGACY: Byte = 0x05
    const val HRP = "ltc"

    private val KEY_VERSIONS: Map<Int, ScriptType?> = mapOf(
        0x019DA462 to ScriptType.P2PKH, // Ltub (BIP44)
        0x01B26EF6 to ScriptType.P2SH_P2WPKH, // Mtub (BIP49)
        0x0488B21E to null, // xpub (ambiguous)
        0x049D7CB2 to ScriptType.P2SH_P2WPKH, // ypub
        0x04B24746 to ScriptType.P2WPKH, // zpub
    )

    /** Script types that can be derived from a single-key Litecoin extended public key. */
    val DERIVABLE_SCRIPT_TYPES = listOf(ScriptType.P2WPKH, ScriptType.P2SH_P2WPKH, ScriptType.P2PKH)

    /** Validates a mainnet address (checksum included); legacy `3…` is converted to `M…`. */
    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        if (text.lowercase().startsWith("$HRP" + "1")) {
            val (version, program) = Bech32Codec.decodeSegwit(HRP, text) ?: return null
            val type = when {
                version == 0 && program.size == 20 -> ScriptType.P2WPKH
                version == 0 -> ScriptType.P2WSH
                version == 1 && program.size == 32 -> ScriptType.P2TR
                else -> return null
            }
            return WatchTarget.Address(ChainFamily.UTXO, LitecoinChain.ID, text.lowercase(), type)
        }
        val payload = Base58Checks.decode(text) ?: return null
        if (payload.size != 21) return null
        val hash = payload.copyOfRange(1, 21)
        return when (payload[0]) {
            P2PKH -> WatchTarget.Address(ChainFamily.UTXO, LitecoinChain.ID, text, ScriptType.P2PKH)

            P2SH -> WatchTarget.Address(ChainFamily.UTXO, LitecoinChain.ID, text, ScriptType.P2SH)

            P2SH_LEGACY -> WatchTarget.Address(
                ChainFamily.UTXO,
                LitecoinChain.ID,
                Base58Checks.encode(byteArrayOf(P2SH) + hash),
                ScriptType.P2SH,
            )

            else -> null
        }
    }

    /**
     * Parses a Litecoin extended public key. [scriptType] overrides the type implied by the prefix
     * (needed for ambiguous `xpub`/`Ltub` keys); default for `xpub` is BIP44 P2PKH.
     */
    fun parseExtendedKey(input: String, scriptType: ScriptType? = null): WatchTarget.ExtendedPublicKey? {
        val (key, implied) = parseExtendedPublicKey(input.trim(), KEY_VERSIONS) ?: return null
        val type = scriptType ?: implied ?: ScriptType.P2PKH
        if (type !in DERIVABLE_SCRIPT_TYPES) return null
        return WatchTarget.ExtendedPublicKey(
            ChainFamily.UTXO,
            LitecoinChain.ID,
            key.encode(DeterministicWallet.xpub),
            type,
        )
    }

    /** `true` when the prefix is Litecoin-specific (`Ltub`/`Mtub`) rather than shared with Bitcoin. */
    fun isLitecoinSpecificKey(input: String): Boolean = input.startsWith("Ltub") || input.startsWith("Mtub")

    /** Address at `<key>/<change>/<index>` (public derivation only). */
    fun derive(key: String, scriptType: ScriptType, change: Boolean, index: Int): String {
        val (account, _) = requireNotNull(parseExtendedPublicKey(key, KEY_VERSIONS)) { "Invalid extended public key" }
        val hash = derivePublicKeyHash(account, change, index)
        return when (scriptType) {
            ScriptType.P2PKH -> Base58Checks.encode(byteArrayOf(P2PKH) + hash)
            ScriptType.P2SH_P2WPKH -> Base58Checks.encode(byteArrayOf(P2SH) + p2shP2wpkhScriptHash(hash))
            ScriptType.P2WPKH -> Bech32Codec.encodeSegwit(HRP, 0, hash)
            else -> throw IllegalArgumentException("$scriptType cannot be derived for Litecoin")
        }
    }
}

/** Litecoin via Esplora (litecoinspace.org). Extended keys are expanded on the device. */
class LitecoinAdapter(
    http: HttpClient,
    esploraUrl: String = LitecoinChain.DEFAULT_ESPLORA_URL,
) : UtxoAdapter(
        chain = LitecoinChain.CHAIN,
        asset = LitecoinChain.LTC,
        backend = EsploraBackend(
            http,
            esploraUrl,
            Throttle(maxConcurrent = 4, minInterval = 100.milliseconds, maxJitter = 150.milliseconds),
        ),
        deriver = { target ->
            { change, index -> LitecoinAddresses.derive(target.key, target.scriptType, change, index) }
        },
        normalize = { address -> LitecoinAddresses.parse(address)?.address ?: address },
    )
