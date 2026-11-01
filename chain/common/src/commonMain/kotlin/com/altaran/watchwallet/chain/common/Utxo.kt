package com.altaran.watchwallet.chain.common

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.DerivedAddress
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.WatchTarget
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.toBigInteger
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock
import kotlin.time.Instant

/** Balance summary of one address as reported by an indexer. */
data class UtxoAddressInfo(val txCount: Int, val balance: Long)

/** One input or output: address (already normalized by the backend) and value in base units. */
data class UtxoIo(val address: String?, val value: Long)

/** Indexer-neutral transaction. */
data class UtxoTx(
    val txid: String,
    val blockTime: Long?,
    val confirmed: Boolean,
    val fee: Long,
    val inputs: List<UtxoIo>,
    val outputs: List<UtxoIo>,
)

class UtxoHistory(val transactions: List<UtxoTx>, val truncated: Boolean)

/** Data source for one UTXO chain. Only single addresses are ever sent. */
interface UtxoBackend {
    suspend fun address(address: String): UtxoAddressInfo

    /** Newest first; stops at a page that contains a known confirmed transaction. */
    suspend fun history(address: String, known: Set<String>, maxPages: Int): UtxoHistory
}

// ---------------------------------------------------------------- extended keys

/**
 * Parses an extended public key whose 4-byte version is listed in [versions]. Returns the key
 * and the script type implied by the version (or `null` when the version is ambiguous).
 */
fun parseExtendedPublicKey(
    value: String,
    versions: Map<Int, ScriptType?>,
): Pair<DeterministicWallet.ExtendedPublicKey, ScriptType?>? {
    if (value.length !in 100..112 || value.any { it.isWhitespace() }) return null
    // Decoded by hand: bitcoin-kmp only accepts the Bitcoin version bytes (xpub/ypub/zpub…).
    val bytes = Base58Checks.decode(value) ?: return null
    if (bytes.size != EXTENDED_KEY_BYTES) return null
    val prefix = bytes.readInt(0)
    if (prefix !in versions) return null
    val publicKey = bytes.copyOfRange(45, 78)
    if (publicKey[0] != 0x02.toByte() && publicKey[0] != 0x03.toByte()) return null
    val key = DeterministicWallet.ExtendedPublicKey(
        publickeybytes = ByteVector(publicKey),
        chaincode = ByteVector32(bytes.copyOfRange(13, 45)),
        depth = bytes[4].toInt() and 0xFF,
        path = KeyPath.empty.derive(bytes.readInt(9).toLong() and 0xFFFFFFFFL),
        parent = bytes.readInt(5).toLong() and 0xFFFFFFFFL,
    )
    // Accessing the public key validates that the bytes are a point on secp256k1.
    runCatching { key.publicKey.also { check(it.isValid()) } }.getOrNull() ?: return null
    return key to versions[prefix]
}

private const val EXTENDED_KEY_BYTES = 78

private fun ByteArray.readInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or (this[offset + 3].toInt() and 0xFF)

/** HASH160 of the compressed public key at `<account>/<change>/<index>` (public derivation only). */
fun derivePublicKeyHash(account: DeterministicWallet.ExtendedPublicKey, change: Boolean, index: Int): ByteArray =
    account.derivePublicKey(if (change) 1L else 0L).derivePublicKey(index.toLong()).publicKey.hash160()

/** HASH160 of the P2SH-P2WPKH redeem script `0x0014<keyhash>` (BIP49). */
fun p2shP2wpkhScriptHash(keyHash: ByteArray): ByteArray = Crypto.hash160(byteArrayOf(0x00, 0x14) + keyHash)

// ---------------------------------------------------------------- adapter

/**
 * Shared adapter for Bitcoin-like UTXO chains (Litecoin, Dogecoin, Bitcoin Cash). Extended public
 * keys are expanded on the device: receive (`/0/i`) and change (`/1/i`) chains are scanned one
 * address at a time until `gapLimit` consecutive unused addresses.
 *
 * @param deriver derives the address at (change, index) for an extended-key target.
 * @param normalize canonical form of a user address, matching what the backend reports.
 */
open class UtxoAdapter(
    private val chain: Chain,
    private val asset: Asset,
    private val backend: UtxoBackend,
    private val deriver: (WatchTarget.ExtendedPublicKey) -> ((change: Boolean, index: Int) -> String),
    private val normalize: (String) -> String = { it },
) : ChainAdapter {
    override val family = ChainFamily.UTXO
    override val chains = listOf(chain)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall(chain.name) {
        when (target) {
            is WatchTarget.Address -> {
                val info = backend.address(normalize(target.address))
                probeResult(info.txCount, info.balance, usedAddresses = null)
            }

            is WatchTarget.ExtendedPublicKey -> {
                val derive = deriver(target)
                val quick = fetchAll((0 until QUICK_PROBE_ADDRESSES).map { derive(false, it) })
                if (quick.none { it.txCount > 0 }) {
                    probeResult(0, 0, usedAddresses = 0)
                } else {
                    val discovered = discover(target)
                    val used = discovered.filter { it.info.txCount > 0 }
                    probeResult(used.sumOf { it.info.txCount }, discovered.sumOf { it.info.balance }, used.size)
                }
            }
        }
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall(chain.name) {
        val scanned = when (target) {
            is WatchTarget.Address -> {
                val address = normalize(target.address)
                listOf(Scanned(address, change = false, index = 0, info = backend.address(address)))
            }

            is WatchTarget.ExtendedPublicKey -> {
                discover(target)
            }
        }
        val ours = scanned.map { it.address }.toSet()
        var truncated = false
        val transactions = LinkedHashMap<String, UtxoTx>()
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES_PER_ADDRESS
        for (address in scanned.filter { it.info.txCount > 0 }) {
            val history = backend.history(address.address, knownTransactions, maxPages)
            truncated = truncated || history.truncated
            history.transactions.forEach { transactions.getOrPut(it.txid) { it } }
        }
        val active = scanned.any { it.info.txCount > 0 }
        SyncResult(
            balances = listOf(amount(scanned.sumOf { it.info.balance })),
            transactions = transactions.values.mapNotNull { utxoChainTransaction(chain, asset, it, ours) },
            derivedAddresses = if (target is WatchTarget.ExtendedPublicKey) {
                scanned.map { DerivedAddress(it.address, it.change, it.index, used = it.info.txCount > 0) }
            } else {
                emptyList()
            },
            activeChains = if (active) listOf(chain.id) else emptyList(),
            historyTruncated = truncated,
        )
    }

    private class Scanned(val address: String, val change: Boolean, val index: Int, val info: UtxoAddressInfo)

    private suspend fun discover(target: WatchTarget.ExtendedPublicKey): List<Scanned> {
        val derive = try {
            deriver(target)
        } catch (e: IllegalArgumentException) {
            throw ChainDataException("Invalid extended public key", e)
        }
        return listOf(false, true).flatMap { change ->
            val result = mutableListOf<Scanned>()
            var unusedInRow = 0
            var nextIndex = 0
            while (unusedInRow < target.gapLimit && nextIndex < MAX_DERIVED_PER_CHAIN) {
                val batch = (nextIndex until nextIndex + target.gapLimit).map { it to derive(change, it) }
                val infos = fetchAll(batch.map { it.second })
                batch.zip(infos).forEach { (pair, info) ->
                    result += Scanned(pair.second, change, pair.first, info)
                    unusedInRow = if (info.txCount > 0) 0 else unusedInRow + 1
                }
                nextIndex += target.gapLimit
            }
            result
        }
    }

    private suspend fun fetchAll(addresses: List<String>): List<UtxoAddressInfo> = coroutineScope {
        addresses.map { async { backend.address(it) } }.awaitAll()
    }

    private fun amount(balance: Long) = AssetAmount(asset, TokenAmount(balance.toBigInteger(), asset.decimals))

    private fun probeResult(txCount: Int, balance: Long, usedAddresses: Int?) = ProbeResult(
        hasActivity = txCount > 0,
        activeChains = if (txCount > 0) listOf(chain.id) else emptyList(),
        balances = listOf(amount(balance)),
        transactionCount = txCount,
        usedAddressCount = usedAddresses,
    )

    companion object {
        const val QUICK_PROBE_ADDRESSES = 3
        const val MAX_DERIVED_PER_CHAIN = 2_000
        const val MAX_PAGES_PER_ADDRESS = 40
        const val FIRST_SYNC_PAGES = 4
    }
}

/** Net effect of a transaction on a set of own addresses, or `null` if it does not touch them. */
fun utxoChainTransaction(chain: Chain, asset: Asset, tx: UtxoTx, ours: Set<String>): ChainTransaction? {
    val spent = tx.inputs.sumOf { if (it.address in ours) it.value else 0L }
    val received = tx.outputs.sumOf { if (it.address in ours) it.value else 0L }
    if (spent == 0L && received == 0L) return null
    val weFunded = spent > 0
    return ChainTransaction(
        chainId = chain.id,
        hash = tx.txid,
        timestamp = tx.blockTime?.let { Instant.fromEpochSeconds(it) } ?: Clock.System.now(),
        asset = asset,
        delta = BigInteger.fromLong(received - spent),
        fee = if (weFunded) BigInteger.fromLong(tx.fee) else null,
        isSelfTransfer = weFunded && tx.outputs.all { it.address in ours },
        confirmed = tx.confirmed,
    )
}
