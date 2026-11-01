package com.altaran.watchwallet.chain.polkadot

import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.Base58Checks
import com.altaran.watchwallet.chain.common.Hex
import com.altaran.watchwallet.chain.common.JsonRpcClient
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.content
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import com.ionspin.kotlin.bignum.integer.BigInteger
import fr.acinq.bitcoin.Base58
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.kotlincrypto.hash.blake2.BLAKE2b
import kotlin.time.Duration.Companion.milliseconds

/**
 * A Substrate relay chain with its SS58 prefix and public RPCs. Since the Asset Hub migration
 * (Polkadot and Kusama, 2025) account balances live on Asset Hub; the relay chain keeps only
 * leftovers, so both are read and summed.
 */
data class PolkadotNetwork(
    val id: ChainId,
    val name: String,
    val ss58Prefix: Int,
    val symbol: String,
    val decimals: Int,
    val coingeckoId: String,
    val rpcUrl: String,
    val assetHubRpcUrl: String,
    val explorer: String,
    val color: Long,
) {
    val chain: Chain
        get() = Chain(
            id = id,
            family = ChainFamily.POLKADOT,
            name = name,
            nativeSymbol = symbol,
            nativeDecimals = decimals,
            nativePriceId = coingeckoId,
            explorerTxUrl = "$explorer/extrinsic/{tx}",
            explorerAddressUrl = "$explorer/account/{address}",
            color = color,
        )

    val asset: Asset get() = Asset(AssetId.native(id), id, symbol, name, decimals, coingeckoId)
}

object PolkadotChains {
    val POLKADOT = PolkadotNetwork(
        ChainId("polkadot"),
        "Polkadot",
        0,
        "DOT",
        10,
        "polkadot",
        "https://rpc.polkadot.io",
        "https://polkadot-asset-hub-rpc.polkadot.io",
        "https://assethub-polkadot.subscan.io",
        0xFFE6007A,
    )
    val KUSAMA = PolkadotNetwork(
        ChainId("kusama"),
        "Kusama",
        2,
        "KSM",
        12,
        "kusama",
        "https://kusama-rpc.polkadot.io",
        "https://kusama-asset-hub-rpc.polkadot.io",
        "https://assethub-kusama.subscan.io",
        0xFF000000,
    )
    val ALL = listOf(POLKADOT, KUSAMA)

    /** The generic Substrate prefix (`5…`), valid on every network after re-encoding. */
    const val GENERIC_PREFIX = 42
}

/**
 * SS58 addresses: Base58 of `prefix ‖ 32-byte account id ‖ checksum`, the checksum being the
 * first two bytes of BLAKE2b-512("SS58PRE" ‖ prefix ‖ account id).
 */
object PolkadotAddresses {
    private val SS58_PRE = "SS58PRE".encodeToByteArray()

    class Decoded(val prefix: Int, val accountId: ByteArray)

    /** Polkadot (`1…`) and Kusama addresses; generic `5…` addresses need [parseGeneric]. */
    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        val decoded = decode(text) ?: return null
        val network = PolkadotChains.ALL.firstOrNull { it.ss58Prefix == decoded.prefix } ?: return null
        return WatchTarget.Address(ChainFamily.POLKADOT, network.id, text)
    }

    /** A generic Substrate address (prefix 42) re-encoded for [network]. */
    fun parseGeneric(input: String, network: PolkadotNetwork): WatchTarget.Address? {
        val decoded = decode(input.trim()) ?: return null
        if (decoded.prefix != PolkadotChains.GENERIC_PREFIX) return null
        return WatchTarget.Address(ChainFamily.POLKADOT, network.id, encode(decoded.accountId, network.ss58Prefix))
    }

    fun decode(text: String): Decoded? {
        val bytes = Base58Checks.decodePlain(text) ?: return null
        if (bytes.isEmpty()) return null
        val first = bytes[0].toInt() and 0xFF
        val (prefix, prefixLength) = when {
            first < 64 -> {
                first to 1
            }

            first < 128 && bytes.size > 1 -> {
                val second = bytes[1].toInt() and 0xFF
                (((first and 0x3F) shl 2) or (second shr 6) or ((second and 0x3F) shl 8)) to 2
            }

            else -> {
                return null
            }
        }
        if (bytes.size != prefixLength + 32 + 2) return null
        val body = bytes.copyOfRange(0, bytes.size - 2)
        val checksum = BLAKE2b(512).digest(SS58_PRE + body)
        if (checksum[0] != bytes[bytes.size - 2] || checksum[1] != bytes[bytes.size - 1]) return null
        return Decoded(prefix, body.copyOfRange(prefixLength, body.size))
    }

    /** Encodes an account id with a simple (< 64) prefix. */
    fun encode(accountId: ByteArray, prefix: Int): String {
        require(prefix in 0..63 && accountId.size == 32)
        val body = byteArrayOf(prefix.toByte()) + accountId
        val checksum = BLAKE2b(512).digest(SS58_PRE + body)
        return Base58.encode(body + checksum.copyOfRange(0, 2))
    }
}

/** Free / reserved / frozen balance of an account (base units). */
data class PolkadotAccountBalances(val free: BigInteger, val reserved: BigInteger, val frozen: BigInteger) {
    /** Everything the account owns: free (incl. frozen/staked) + reserved. */
    val total: BigInteger get() = free + reserved

    operator fun plus(other: PolkadotAccountBalances) =
        PolkadotAccountBalances(free + other.free, reserved + other.reserved, frozen + other.frozen)
}

/**
 * Polkadot and Kusama via the public Substrate JSON-RPC: `state_getStorage` of `System.Account`
 * decoded from SCALE, on Asset Hub plus the relay chain (see [PolkadotNetwork]). The balance is
 * free + reserved (staked DOT is frozen inside free).
 *
 * History is not available: there is no keyless public indexer, so [sync] returns no
 * transactions. Nomination-pool stakes (held by the pool account) are not included.
 */
class PolkadotAdapter(
    http: HttpClient,
    networks: List<PolkadotNetwork> = PolkadotChains.ALL,
) : ChainAdapter {
    private val networks = networks
    private val clients = networks.associate { network ->
        network.id to listOf(network.assetHubRpcUrl, network.rpcUrl).map { url ->
            JsonRpcClient(http, listOf(url), Throttle(maxConcurrent = 2, minInterval = 100.milliseconds))
        }
    }

    override val family = ChainFamily.POLKADOT
    override val chains = networks.map { it.chain }

    /** Polkadot has no keyless history source; callers can hide the history section. */
    val supportsHistory: Boolean = false

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("Substrate RPC") {
        val (network, balances) = load(target)
        val active = balances != null
        ProbeResult(
            hasActivity = active,
            activeChains = if (active) listOf(network.id) else emptyList(),
            balances = listOf(amountOf(network.asset, balances?.total ?: BigInteger.ZERO)),
            transactionCount = null,
        )
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall(
        "Substrate RPC",
    ) {
        val (network, balances) = load(target)
        SyncResult(
            balances = listOf(amountOf(network.asset, balances?.total ?: BigInteger.ZERO)),
            transactions = emptyList(),
            activeChains = if (balances != null) listOf(network.id) else emptyList(),
        )
    }

    /** Free / reserved / frozen breakdown (frozen = locked by staking, vesting, governance). */
    suspend fun accountBalances(
        target: WatchTarget,
    ): PolkadotAccountBalances? = chainCall("Substrate RPC") { load(target).second }

    /** Locked (frozen) part of the balance, for display next to the total. */
    suspend fun lockedBalance(target: WatchTarget): TokenAmount = chainCall("Substrate RPC") {
        val (network, balances) = load(target)
        TokenAmount(balances?.frozen ?: BigInteger.ZERO, network.decimals)
    }

    private suspend fun load(target: WatchTarget): Pair<PolkadotNetwork, PolkadotAccountBalances?> {
        val address =
            (target as? WatchTarget.Address)?.address ?: throw ChainDataException("Polkadot supports addresses only")
        val decoded = PolkadotAddresses.decode(address) ?: throw ChainDataException("Invalid SS58 address")
        val network = networks.firstOrNull { it.id == target.chainId }
            ?: networks.firstOrNull { it.ss58Prefix == decoded.prefix }
            ?: throw ChainDataException("Unsupported Substrate network")
        val key = "0x" + Hex.encode(SubstrateStorage.systemAccountKey(decoded.accountId))
        val parts = clients.getValue(network.id).mapNotNull { client ->
            client.call("state_getStorage", JsonArray(listOf(JsonPrimitive(key)))).content?.let(SubstrateStorage::decodeAccountInfo)
        }
        return network to parts.reduceOrNull(PolkadotAccountBalances::plus)
    }
}

/** Storage keys and SCALE decoding for `System.Account`. */
internal object SubstrateStorage {
    private val SYSTEM_ACCOUNT_PREFIX = twox128("System".encodeToByteArray()) + twox128("Account".encodeToByteArray())

    /** `twox128("System") ‖ twox128("Account") ‖ blake2_128(id) ‖ id` (Blake2_128Concat hasher). */
    fun systemAccountKey(accountId: ByteArray): ByteArray =
        SYSTEM_ACCOUNT_PREFIX + BLAKE2b(128).digest(accountId) + accountId

    fun twox128(data: ByteArray): ByteArray =
        XxHash64.hash(data, 0uL).toLittleEndian() + XxHash64.hash(data, 1uL).toLittleEndian()

    /**
     * AccountInfo: nonce, consumers, providers, sufficients (u32 each), then AccountData: free,
     * reserved, frozen (u128 LE each; older runtimes: misc_frozen, fee_frozen).
     */
    fun decodeAccountInfo(hex: String): PolkadotAccountBalances? {
        val bytes = Hex.decode(hex) ?: return null
        if (bytes.size < 64) return null
        fun u128(offset: Int): BigInteger {
            val be = bytes.copyOfRange(offset, offset + 16).reversedArray()
            return BigInteger.parseString(Hex.encode(be).trimStart('0').ifEmpty { "0" }, 16)
        }
        return PolkadotAccountBalances(free = u128(16), reserved = u128(32), frozen = u128(48))
    }

    private fun ULong.toLittleEndian(): ByteArray = ByteArray(8) { ((this shr (8 * it)) and 0xFFuL).toByte() }
}

/** xxHash64 (non-cryptographic), as used by Substrate's `Twox` storage hashers. */
internal object XxHash64 {
    private const val P1 = 0x9E3779B185EBCA87uL
    private const val P2 = 0xC2B2AE3D27D4EB4FuL
    private const val P3 = 0x165667B19E3779F9uL
    private const val P4 = 0x85EBCA77C2B2AE63uL
    private const val P5 = 0x27D4EB2F165667C5uL

    private fun ULong.rotl(bits: Int): ULong = (this shl bits) or (this shr (64 - bits))

    private fun round(acc: ULong, input: ULong): ULong = (acc + input * P2).rotl(31) * P1

    private fun merge(acc: ULong, value: ULong): ULong = (acc xor round(0uL, value)) * P1 + P4

    private fun ByteArray.u64(i: Int): ULong {
        var v = 0uL
        for (k in 7 downTo 0) v = (v shl 8) or (this[i + k].toULong() and 0xFFuL)
        return v
    }

    private fun ByteArray.u32(i: Int): ULong {
        var v = 0uL
        for (k in 3 downTo 0) v = (v shl 8) or (this[i + k].toULong() and 0xFFuL)
        return v
    }

    fun hash(data: ByteArray, seed: ULong): ULong {
        var i = 0
        val length = data.size
        var h: ULong
        if (length >= 32) {
            var v1 = seed + P1 + P2
            var v2 = seed + P2
            var v3 = seed
            var v4 = seed - P1
            while (i <= length - 32) {
                v1 = round(v1, data.u64(i))
                v2 = round(v2, data.u64(i + 8))
                v3 = round(v3, data.u64(i + 16))
                v4 = round(v4, data.u64(i + 24))
                i += 32
            }
            h = v1.rotl(1) + v2.rotl(7) + v3.rotl(12) + v4.rotl(18)
            h = merge(h, v1)
            h = merge(h, v2)
            h = merge(h, v3)
            h = merge(h, v4)
        } else {
            h = seed + P5
        }
        h += length.toULong()
        while (i <= length - 8) {
            h = (h xor round(0uL, data.u64(i))).rotl(27) * P1 + P4
            i += 8
        }
        if (i <= length - 4) {
            h = (h xor (data.u32(i) * P1)).rotl(23) * P2 + P3
            i += 4
        }
        while (i < length) {
            h = (h xor ((data[i].toULong() and 0xFFuL) * P5)).rotl(11) * P1
            i++
        }
        h = h xor (h shr 33)
        h *= P2
        h = h xor (h shr 29)
        h *= P3
        h = h xor (h shr 32)
        return h
    }
}
