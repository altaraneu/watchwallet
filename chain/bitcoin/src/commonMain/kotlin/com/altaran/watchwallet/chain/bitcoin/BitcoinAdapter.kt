package com.altaran.watchwallet.chain.bitcoin

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncOptions
import com.altaran.watchwallet.chain.api.SyncPartSink
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.core.crypto.BitcoinAddresses
import com.altaran.watchwallet.core.crypto.ExtendedKeys
import com.altaran.watchwallet.core.endpoints.BundledEndpoints
import com.altaran.watchwallet.core.endpoints.EndpointProtocol
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.DerivedAddress
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import com.altaran.watchwallet.core.network.safeDescription
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.toBigInteger
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object BitcoinChain {
    val CHAIN = Chain(
        id = ChainId.BITCOIN,
        family = ChainFamily.UTXO,
        name = "Bitcoin",
        nativeSymbol = "BTC",
        nativeDecimals = 8,
        nativePriceId = "bitcoin",
        explorerTxUrl = "https://mempool.space/tx/{tx}",
        explorerAddressUrl = "https://mempool.space/address/{address}",
        color = 0xFFF7931A,
    )

    val BTC = Asset(
        id = AssetId.native(ChainId.BITCOIN),
        chainId = ChainId.BITCOIN,
        symbol = "BTC",
        name = "Bitcoin",
        decimals = 8,
        priceId = "bitcoin",
    )
}

/**
 * Bitcoin via Esplora. Extended public keys are expanded locally: receive (`/0/i`) and change
 * (`/1/i`) chains are scanned until `gapLimit` consecutive unused addresses.
 */
class BitcoinAdapter(
    http: HttpClient,
    esploraUrls: List<String> = BundledEndpoints.urls(ChainId.BITCOIN, EndpointProtocol.ESPLORA),
) : ChainAdapter {
    constructor(http: HttpClient, esploraUrl: String) : this(http, listOf(esploraUrl))

    // Public instances rate-limit aggressively; jitter also decorrelates xpub address lookups.
    private val esplora = EsploraClient(http, esploraUrls, Throttle(maxConcurrent = 4, minInterval = 80.milliseconds, maxJitter = 150.milliseconds))

    override val family = ChainFamily.UTXO
    override val chains = listOf(BitcoinChain.CHAIN)
    override val supportsHistoryContinuation = true

    override suspend fun probe(target: WatchTarget): ProbeResult = wrap {
        when (target) {
            is WatchTarget.Address -> {
                val info = esplora.address(target.address)
                probeResult(info.txCount, info.balance, usedAddresses = null)
            }

            is WatchTarget.ExtendedPublicKey -> {
                // Cheap pre-check of the first receive addresses before a full gap-limit scan.
                val key = parseKey(target)
                val quick = (0 until QUICK_PROBE_ADDRESSES).map { BitcoinAddresses.derive(key, target.scriptType, false, it) }
                val quickInfo = fetchAll(quick)
                if (quickInfo.none { it.txCount > 0 }) {
                    probeResult(0, 0, usedAddresses = 0)
                } else {
                    val discovered = discover(target)
                    val used = discovered.filter { it.info.txCount > 0 }
                    probeResult(used.sumOf { it.info.txCount }, discovered.sumOf { it.info.balance }, used.size)
                }
            }
        }
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult =
        sync(target, knownTransactions, continueHistory = false)

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>, continueHistory: Boolean): SyncResult =
        scan(target, knownTransactions, continueHistory, usedBefore = null)

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>, options: SyncOptions, sink: SyncPartSink): SyncResult = scan(
        target,
        knownTransactions,
        options.continueHistory,
        options.usedAddresses.takeIf { target is WatchTarget.ExtendedPublicKey && knownTransactions.isNotEmpty() },
    )

    override suspend fun resetHostHealth() = esplora.resetHostHealth()

    /**
     * @param usedBefore extended keys: addresses earlier syncs found with history. An address that
     * has history now but is not among them (it just entered the gap window) must be walked to its
     * end: stopping at a known transaction would skip its older ones for good, since known
     * transactions are all later syncs ever stop at. Known transactions it reaches are corrected,
     * because their amounts did not count this address yet.
     */
    private suspend fun scan(target: WatchTarget, knownTransactions: Set<String>, continueHistory: Boolean, usedBefore: Set<String>?): SyncResult = wrap {
        val scanned: List<ScannedAddress> = when (target) {
            is WatchTarget.Address -> listOf(ScannedAddress(target.address, change = false, index = 0, info = esplora.address(target.address)))
            is WatchTarget.ExtendedPublicKey -> discover(target)
        }
        val ours = scanned.map { it.address }.toSet()
        val balance = scanned.sumOf { it.info.balance }

        var truncated = false
        val transactions = LinkedHashMap<String, EsploraTx>()
        // First sync: only recent pages per address, so large wallets show up quickly; later syncs
        // stop at known transactions anyway. Older history is marked as truncated and fetched in
        // chunks by the following syncs (continueHistory).
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES_PER_ADDRESS
        val corrected = HashSet<String>()
        for (address in scanned.filter { it.info.txCount > 0 }) {
            val isNew = usedBefore != null && address.address !in usedBefore
            val history = when {
                isNew -> fetchHistory(address.address, emptySet(), maxPages)
                continueHistory && knownTransactions.isNotEmpty() -> backfillHistory(address.address, knownTransactions)
                else -> fetchHistory(address.address, knownTransactions, maxPages)
            }
            truncated = truncated || history.truncated
            history.transactions.forEach { transactions.getOrPut(it.txid) { it } }
            if (isNew) history.transactions.filter { it.txid in knownTransactions }.mapTo(corrected) { it.txid }
        }
        val (changed, fresh) = transactions.values.mapNotNull { toChainTransaction(it, ours) }.partition { it.hash in corrected }

        SyncResult(
            balances = listOf(AssetAmount(BitcoinChain.BTC, TokenAmount(balance.toBigInteger(), BitcoinChain.BTC.decimals))),
            transactions = fresh,
            correctedTransactions = changed,
            derivedAddresses = if (target is WatchTarget.ExtendedPublicKey) {
                scanned.map { DerivedAddress(it.address, it.change, it.index, used = it.info.txCount > 0) }
            } else {
                emptyList()
            },
            activeChains = if (scanned.any { it.info.txCount > 0 }) listOf(ChainId.BITCOIN) else emptyList(),
            historyTruncated = truncated,
        )
    }

    private class ScannedAddress(val address: String, val change: Boolean, val index: Int, val info: EsploraAddress)

    private class History(val transactions: List<EsploraTx>, val truncated: Boolean)

    private fun parseKey(target: WatchTarget.ExtendedPublicKey) =
        ExtendedKeys.parse(target.key)?.key ?: throw ChainDataException("Invalid extended public key")

    /** Gap-limit discovery of both chains. */
    private suspend fun discover(target: WatchTarget.ExtendedPublicKey): List<ScannedAddress> {
        val key = parseKey(target)
        return listOf(false, true).flatMap { change ->
            val result = mutableListOf<ScannedAddress>()
            var unusedInRow = 0
            var nextIndex = 0
            while (unusedInRow < target.gapLimit && nextIndex < MAX_DERIVED_PER_CHAIN) {
                val batch = (nextIndex until nextIndex + target.gapLimit).map { index ->
                    index to BitcoinAddresses.derive(key, target.scriptType, change, index)
                }
                val infos = fetchAll(batch.map { it.second })
                batch.zip(infos).forEach { (pair, info) ->
                    result += ScannedAddress(pair.second, change, pair.first, info)
                    unusedInRow = if (info.txCount > 0) 0 else unusedInRow + 1
                }
                nextIndex += target.gapLimit
            }
            result
        }
    }

    private suspend fun fetchAll(addresses: List<String>): List<EsploraAddress> = coroutineScope {
        addresses.map { async { esplora.address(it) } }.awaitAll()
    }

    private suspend fun fetchHistory(address: String, known: Set<String>, maxPages: Int): History {
        val collected = mutableListOf<EsploraTx>()
        var lastSeen: String? = null
        repeat(maxPages) {
            val page = esplora.transactions(address, lastSeen)
            collected += page
            val confirmed = page.filter { it.status.confirmed }
            // Stop at the first page that reaches already known confirmed transactions.
            if (confirmed.size < EsploraClient.PAGE_SIZE || confirmed.any { it.txid in known }) {
                return History(collected, truncated = false)
            }
            lastSeen = confirmed.last().txid
        }
        return History(collected, truncated = true)
    }

    /**
     * Walks the address history past the known transactions and collects up to
     * [BACKFILL_NEW_PAGES] pages of older ones. Known pages are walked (cheaply, 25 per request)
     * but do not count against the budget.
     */
    private suspend fun backfillHistory(address: String, known: Set<String>): History {
        val collected = mutableListOf<EsploraTx>()
        var lastSeen: String? = null
        var newPages = 0
        repeat(MAX_BACKFILL_WALK) {
            val page = esplora.transactions(address, lastSeen)
            val confirmed = page.filter { it.status.confirmed }
            val fresh = page.filter { it.txid !in known }
            collected += fresh
            if (confirmed.size < EsploraClient.PAGE_SIZE) return History(collected, truncated = false)
            if (fresh.isNotEmpty() && ++newPages >= BACKFILL_NEW_PAGES) return History(collected, truncated = true)
            lastSeen = confirmed.last().txid
        }
        return History(collected, truncated = true)
    }

    private fun probeResult(txCount: Int, balance: Long, usedAddresses: Int?) = ProbeResult(
        hasActivity = txCount > 0,
        activeChains = if (txCount > 0) listOf(ChainId.BITCOIN) else emptyList(),
        balances = listOf(AssetAmount(BitcoinChain.BTC, TokenAmount(balance.toBigInteger(), BitcoinChain.BTC.decimals))),
        transactionCount = txCount,
        usedAddressCount = usedAddresses,
    )

    private suspend fun <T> wrap(block: suspend () -> T): T = try {
        block()
    } catch (e: ChainDataException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (
        // Dropped on purpose: its message holds the URL with the address.
        @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
    ) {
        throw ChainDataException("Bitcoin data source failed: ${e.safeDescription()}")
    }

    private companion object {
        const val QUICK_PROBE_ADDRESSES = 3
        const val MAX_DERIVED_PER_CHAIN = 2_000
        const val MAX_PAGES_PER_ADDRESS = 40
        const val FIRST_SYNC_PAGES = 4
        const val BACKFILL_NEW_PAGES = 8
        const val MAX_BACKFILL_WALK = 400
    }
}

/** Net effect of a transaction on a set of own addresses, or `null` if it does not touch them. */
internal fun toChainTransaction(tx: EsploraTx, ours: Set<String>): ChainTransaction? {
    val spent = tx.vin.sumOf { vin -> vin.prevout?.takeIf { it.address in ours }?.value ?: 0L }
    val received = tx.vout.sumOf { out -> if (out.address in ours) out.value else 0L }
    if (spent == 0L && received == 0L) return null
    val weFunded = spent > 0
    val allOutputsOurs = tx.vout.all { it.address in ours }
    return ChainTransaction(
        chainId = ChainId.BITCOIN,
        hash = tx.txid,
        timestamp = tx.status.blockTime?.let { Instant.fromEpochSeconds(it) } ?: Clock.System.now(),
        asset = BitcoinChain.BTC,
        delta = BigInteger.fromLong(received - spent),
        fee = if (weFunded) BigInteger.fromLong(tx.fee) else null,
        isSelfTransfer = weFunded && allOutputsOurs,
        confirmed = tx.status.confirmed,
    )
}
