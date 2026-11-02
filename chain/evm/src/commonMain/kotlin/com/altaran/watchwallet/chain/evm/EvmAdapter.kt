package com.altaran.watchwallet.chain.evm

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncOptions
import com.altaran.watchwallet.chain.api.SyncPart
import com.altaran.watchwallet.chain.api.SyncPartSink
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.HostHealth
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One `0x` address across the EVM networks. Native balance and activity come from public JSON-RPC;
 * tokens and history from Blockscout where the network has an instance.
 *
 * A routine refresh only touches the wallet's active networks. All networks are searched for
 * activity on the first sync, once per [DISCOVERY_INTERVAL] and on a manual refresh. Every network
 * works on its own: its result is stored as soon as it is known, balances before history, and a
 * slow or failing network neither delays the others nor loses their results. Stored data of a
 * network is only replaced by what was really fetched.
 */
class EvmAdapter(
    http: HttpClient,
    private val networks: List<EvmNetwork> = EvmNetworks.DEFAULT,
    private val now: () -> Instant = { Clock.System.now() },
    /** Balance stage of one network; each history page has the deadline of the HTTP client. */
    private val networkDeadline: Duration = 20.seconds,
) : ChainAdapter {
    private val health = HostHealth(now)
    private val rpc = EvmRpcClient(http, health)
    private val blockscout = BlockscoutClient(http, health)

    override val family = ChainFamily.EVM
    override val chains = networks.map { it.chain }
    override val supportsHistoryContinuation = true

    private class OnChain(val balance: BigInteger, val nonce: BigInteger) {
        val active: Boolean get() = !balance.isZero() || !nonce.isZero()
    }

    /** How the sync of one network ended. */
    private class Outcome(
        val network: EvmNetwork,
        val wasActive: Boolean,
        val reachable: Boolean = true,
        val active: Boolean = false,
        /** Inactive for sure: the indexer could be asked (receive-only addresses have no nonce). */
        val conclusive: Boolean = true,
        val failed: Boolean = false,
        val truncated: Boolean = false,
    )

    override suspend fun probe(target: WatchTarget): ProbeResult = coroutineScope {
        val address = addressOf(target)
        val results = networks.map { network ->
            async { withTimeoutOrNull(PROBE_NETWORK_TIMEOUT) { probeNetwork(network, address) } }
        }.awaitAll()
        if (results.all { it == null }) throw ChainDataException("No EVM network reachable")
        val active = results.filterNotNull().filter { it.active }
        ProbeResult(
            hasActivity = active.isNotEmpty(),
            activeChains = active.map { it.network.id },
            balances = active.filter { !it.balance.isZero() }.map { AssetAmount(it.network.nativeAsset, TokenAmount(it.balance, 18)) },
            transactionCount = active.sumOf { it.transactionCount },
        )
    }

    private class Probed(val network: EvmNetwork, val balance: BigInteger, val active: Boolean, val transactionCount: Int)

    /** `null` when the network cannot be reached. */
    private suspend fun probeNetwork(network: EvmNetwork, address: String): Probed? {
        val onChain = onChain(network, address) ?: return null
        if (onChain.active) return Probed(network, onChain.balance, true, onChain.nonce.intValue(exactRequired = false))
        // Receive-only addresses (e.g. airdropped tokens) have zero nonce and native balance.
        val counters = counters(network, address)
        val count = (counters?.transactions?.toIntOrNull() ?: 0) + (counters?.tokenTransfers?.toIntOrNull() ?: 0)
        return Probed(network, onChain.balance, count > 0, count)
    }

    /** One complete result at once; [sync] with a sink is what the app uses. */
    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult =
        sync(target, knownTransactions, continueHistory = false)

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>, continueHistory: Boolean): SyncResult {
        val parts = mutableListOf<SyncPart>()
        val lock = Mutex()
        val options = SyncOptions(continueHistory = continueHistory)
        val outcomes = run(addressOf(target), knownTransactions, options) { part -> lock.withLock { parts += part } }
        if (allFailed(outcomes)) throw ChainDataException("No EVM network could be synced")
        return SyncResult(
            balances = parts.flatMap { it.balances },
            transactions = parts.flatMap { it.transactions },
            activeChains = outcomes.filter { it.active }.map { it.network.id },
            historyTruncated = outcomes.any { it.truncated },
            syncedChains = outcomes.filter { it.reachable && it.conclusive && !it.failed }.map { it.network.id },
        )
    }

    /** Networks that fail keep their stored data and stay active; the next sync tries them again. */
    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>, options: SyncOptions, sink: SyncPartSink): SyncResult {
        val outcomes = run(addressOf(target), knownTransactions, options, sink)
        return SyncResult(
            balances = emptyList(),
            transactions = emptyList(),
            historyTruncated = outcomes.any { it.truncated },
            storedProgressively = true,
            discoveryCompleted = isDiscovery(options) && outcomes.count { !it.reachable } * 2 <= outcomes.size,
            failedChains = outcomes.filter { it.failed || (!it.reachable && it.wasActive) }.map { it.network.id },
            allChainsFailed = allFailed(outcomes),
        )
    }

    private fun addressOf(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("EVM supports addresses only")

    private fun isDiscovery(options: SyncOptions): Boolean =
        options.manual || options.lastDiscoveryAt?.let { now() - it >= DISCOVERY_INTERVAL } != false

    private fun allFailed(outcomes: List<Outcome>): Boolean {
        val relevant = outcomes.filter { it.active || it.wasActive }
        return relevant.isNotEmpty() && relevant.all { it.failed || !it.reachable }
    }

    private suspend fun run(address: String, known: Set<String>, options: SyncOptions, sink: SyncPartSink): List<Outcome> = coroutineScope {
        if (options.manual) health.reset()
        val discovery = isDiscovery(options)
        val targets = if (discovery) networks else networks.filter { it.id in options.activeChains }
        val outcomes = targets.map { network -> async { syncNetwork(network, address, known, options, discovery, sink) } }.awaitAll()
        if (outcomes.isNotEmpty() && outcomes.none { it.reachable }) throw ChainDataException("No EVM network reachable")
        outcomes
    }

    /** `null` for a failure of [block]; only a cancellation from outside passes. */
    private suspend fun <T : Any> attempt(trace: (String) -> Unit, label: String, block: suspend () -> T): T? = try {
        coroutineScope { block() }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        trace("$label cancelled ${e::class.simpleName}")
        null
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        // Only the type: messages of network errors contain the URL, and with it the address.
        trace("$label failed ${e::class.simpleName}")
        null
    }

    private suspend fun onChain(network: EvmNetwork, address: String, trace: (String) -> Unit = {}): OnChain? =
        withTimeoutOrNull(networkDeadline) {
            attempt(trace, "${network.id.value} rpc") {
                coroutineScope {
                    val balance = async { rpc.balance(network.rpcUrls, address) }
                    val nonce = async { rpc.transactionCount(network.rpcUrls, address) }
                    OnChain(balance.await(), nonce.await())
                }
            }
        }

    /** Counters can be slow for big accounts, so they get their own short timeout. */
    private suspend fun counters(network: EvmNetwork, address: String, trace: (String) -> Unit = {}): BsCounters? {
        if (network.blockscoutUrls.isEmpty()) return null
        return withTimeoutOrNull(COUNTERS_TIMEOUT) {
            attempt(trace, "${network.id.value} counters") { blockscout.counters(network.blockscoutUrls, address) }
        }
    }

    private suspend fun syncNetwork(
        network: EvmNetwork,
        address: String,
        known: Set<String>,
        options: SyncOptions,
        discovery: Boolean,
        sink: SyncPartSink,
    ): Outcome {
        val id = network.id
        val wasActive = id in options.activeChains
        val started = now()
        val onChain = onChain(network, address, options.trace) ?: return Outcome(network, wasActive, reachable = false, active = wasActive)
        val counters = counters(network, address, options.trace)
        val indexed = network.blockscoutUrls.isNotEmpty()
        val indexedRows = (counters?.transactions?.toIntOrNull() ?: 0) + (counters?.tokenTransfers?.toIntOrNull() ?: 0)
        // The stored data says active and the indexer cannot tell: go on, the balances stay safe.
        if (!onChain.active && indexedRows == 0 && !(wasActive && indexed && counters == null)) {
            val conclusive = !indexed || counters != null
            if (wasActive && conclusive) sink.store(SyncPart(id, balancesComplete = true, active = false))
            return Outcome(network, wasActive, conclusive = conclusive)
        }

        val native = AssetAmount(network.nativeAsset, TokenAmount(onChain.balance, 18))
        val previous = known.takeIf { it.isNotEmpty() }?.let { options.chainState[id] }?.let(EvmNetworkState::decode)
        val previouslyTruncated = previous?.cursors?.isNotEmpty() ?: options.continueHistory
        if (!indexed) {
            sink.store(SyncPart(id, listOf(native), balancesComplete = true, active = true))
            return Outcome(network, wasActive, active = true)
        }
        val tokens = withTimeoutOrNull(networkDeadline) {
            attempt(options.trace, "${id.value} tokens") { tokenBalances(network, address) }
        }
        if (tokens == null) {
            // Stored tokens are kept, never replaced by "no tokens"; the native balance is still news.
            sink.store(SyncPart(id, listOf(native), balancesComplete = false, active = true))
            return Outcome(network, wasActive, active = true, failed = true, truncated = previouslyTruncated)
        }
        sink.store(SyncPart(id, listOf(native) + tokens.amounts, balancesComplete = true, active = true))
        options.trace("${id.value} balances ${(now() - started).inWholeMilliseconds}ms")

        // Rebasing tokens change balances without any transaction, so token balances are always
        // fetched; the balances and counters together tell whether there can be new history.
        val fingerprint = counters?.let { "${onChain.balance}|${onChain.nonce}|${it.transactions}|${it.tokenTransfers}|${tokens.fingerprint}".hashCode().toString() }
        val unchanged = !discovery && fingerprint != null && previous?.fingerprint == fingerprint && !previous.pending
        val history = fetchHistory(network, address, known, previous, options, walkHead = !unchanged, keepPartial = known.isEmpty(), trace = options.trace)
            ?: return Outcome(network, wasActive, active = true, failed = true, truncated = previouslyTruncated)
        val state = EvmNetworkState(fingerprint, history.transactions.any { !it.confirmed }, history.cursors)
        if (history.transactions.isNotEmpty() || !unchanged || previous?.cursors != history.cursors) {
            sink.store(SyncPart(id, transactions = history.transactions, historyFetched = !unchanged, state = state.encode()))
        }
        options.trace(
            "${id.value} history ${if (unchanged) "unchanged" else "walked"} rows=${history.transactions.size} " +
                "open=${history.cursors.size} total=${(now() - started).inWholeMilliseconds}ms",
        )
        return Outcome(network, wasActive, active = true, truncated = history.cursors.isNotEmpty())
    }

    private class TokenBalances(val amounts: List<AssetAmount>, val fingerprint: Int)

    private suspend fun tokenBalances(network: EvmNetwork, address: String): TokenBalances {
        val tokenBalances = blockscout.tokenBalances(network.blockscoutUrls, address)
        val amounts = mutableListOf<AssetAmount>()
        tokenBalances.filter { it.token.type == "ERC-20" && it.token.contract != null }.forEach { balance ->
            // Staked POL is held as validator share tokens: report the POL they represent.
            polygonStake(network, balance.token, address)?.let {
                amounts += it
                return@forEach
            }
            val asset = tokenAsset(network, balance.token) ?: return@forEach
            amounts += AssetAmount(asset, TokenAmount.parseRaw(balance.value ?: "0", asset.decimals))
        }
        return TokenBalances(amounts, tokenBalances.map { "${it.token.contract}:${it.value}" }.sorted().hashCode())
    }

    private class History(val transactions: List<ChainTransaction>, val cursors: Map<String, HistoryCursor>)

    /**
     * Fetches what is new in the three history lists and a further chunk of whatever is still open.
     * `null` when nothing can be stored: without [keepPartial], a list that fails while looking for
     * the known transactions fails the whole network, because storing the other lists would make
     * the failed one stop too early next time (their hashes are shared).
     */
    private suspend fun fetchHistory(
        network: EvmNetwork,
        address: String,
        known: Set<String>,
        previous: EvmNetworkState?,
        options: SyncOptions,
        walkHead: Boolean,
        keepPartial: Boolean,
        trace: (String) -> Unit,
    ): History? = coroutineScope {
        val base = network.blockscoutUrls
        // Marked truncated, but nothing remembered (older app version): walk everything from the top.
        val open = previous?.cursors ?: if (options.continueHistory) HistoryList.entries.associate { it.name to HistoryCursor() } else emptyMap()
        val deadline = now() + HISTORY_BUDGET
        fun <T> list(kind: HistoryList, hashOf: (T) -> String?, fetch: suspend (JsonObject?) -> BsPage<T>) = async {
            val label = "${network.id.value} ${kind.name}"
            walkList(open[kind.name], walkHead, keepPartial, known, deadline, hashOf, trace, label, fetch)
        }
        val native = list(HistoryList.NATIVE, BsTransaction::hash) { blockscout.transactions(base, address, it) }
        val internal = list(HistoryList.INTERNAL, BsInternalTransaction::transactionHash) { blockscout.internalTransactions(base, address, it) }
        val tokens = list(HistoryList.TOKENS, BsTokenTransfer::hash) { blockscout.tokenTransfers(base, address, it) }
        val walks = listOf(HistoryList.NATIVE to native.await(), HistoryList.INTERNAL to internal.await(), HistoryList.TOKENS to tokens.await())
        val anyRows = walks.any { it.second.rows.isNotEmpty() }
        val anyFailed = walks.any { it.second.headFailed }
        if (anyFailed && (!keepPartial || !anyRows)) return@coroutineScope null

        val own = address.lowercase()
        val rows = mutableListOf<ChainTransaction>()
        native.await().rows.forEach { tx -> nativeTransaction(network, tx, own)?.let(rows::add) }
        internal.await().rows.forEach { tx -> internalTransaction(network, tx, own)?.let(rows::add) }
        tokens.await().rows.forEach { transfer -> tokenTransaction(network, transfer, own)?.let(rows::add) }
        // Several rows for the same (hash, asset) — e.g. a swap with internal value — are merged.
        val merged = rows.groupBy { it.hash to it.asset.id }.map { (_, group) ->
            group.reduce { acc, row -> acc.copy(delta = acc.delta + row.delta, fee = acc.fee ?: row.fee) }
        }
        History(merged, walks.mapNotNull { (kind, walk) -> walk.cursor?.let { kind.name to it } }.toMap())
    }

    private class ListWalk<T>(val rows: List<T>, val cursor: HistoryCursor?, val headFailed: Boolean = false)

    /**
     * Walks one list, newest first. The head walk ends at a known transaction or at the end of the
     * list. When it is cut off (page limit, time budget or, with [keepPartial], an error) the cursor
     * continues from there to the end of the list, past anything already stored, so no gap can
     * remain. An open [previous] cursor gets a further chunk; its failure only postpones it.
     */
    private suspend fun <T> walkList(
        previous: HistoryCursor?,
        walkHead: Boolean,
        keepPartial: Boolean,
        known: Set<String>,
        deadline: Instant,
        hashOf: (T) -> String?,
        trace: (String) -> Unit,
        label: String,
        fetch: suspend (JsonObject?) -> BsPage<T>,
    ): ListWalk<T> {
        val rows = mutableListOf<T>()
        // An open cursor without position means "from the top" and covers the head by itself.
        if (walkHead && (previous == null || previous.next != null)) {
            var next: JsonObject? = null
            var pages = 0
            while (true) {
                val page = attempt(trace, label) { fetch(next) }
                    ?: return if (keepPartial) ListWalk(rows, HistoryCursor(next), headFailed = true) else ListWalk(emptyList(), previous, headFailed = true)
                rows += page.items
                pages++
                next = page.nextPageParams ?: break
                if (page.items.any { hashOf(it) in known }) break
                if (pages >= MAX_HEAD_PAGES || now() >= deadline) return ListWalk(rows, HistoryCursor(next))
            }
        }
        var cursor = previous ?: return ListWalk(rows, null)
        repeat(CONTINUATION_PAGES) {
            val page = attempt(trace, label) { fetch(cursor.next) } ?: return ListWalk(rows, cursor)
            rows += page.items
            cursor = HistoryCursor(page.nextPageParams ?: return ListWalk(rows, null))
            if (now() >= deadline) return ListWalk(rows, cursor)
        }
        return ListWalk(rows, cursor)
    }

    private fun nativeTransaction(network: EvmNetwork, tx: BsTransaction, own: String): ChainTransaction? {
        val from = tx.from?.hash?.lowercase()
        val to = tx.to?.hash?.lowercase()
        val value = BigInteger.parseString(tx.value ?: "0")
        val fee = BigInteger.parseString(tx.fee?.value ?: "0")
        val failed = tx.status == "error" || (tx.result != null && tx.result != "success")
        val outgoing = from == own
        val incoming = to == own
        if (!outgoing && !incoming) return null
        var delta = BigInteger.ZERO
        if (outgoing) delta -= fee + if (failed) BigInteger.ZERO else value
        if (incoming && !failed) delta += value
        if (delta.isZero() && !outgoing) return null
        return ChainTransaction(
            chainId = network.id,
            hash = tx.hash,
            timestamp = parseTime(tx.timestamp),
            asset = network.nativeAsset,
            delta = delta,
            fee = if (outgoing) fee else null,
            isSelfTransfer = outgoing && incoming,
            confirmed = tx.status != null && tx.status != "pending",
        )
    }

    private fun internalTransaction(network: EvmNetwork, tx: BsInternalTransaction, own: String): ChainTransaction? {
        if (tx.success == false) return null
        val value = BigInteger.parseString(tx.value ?: "0")
        if (value.isZero()) return null
        val delta = when (own) {
            tx.to?.hash?.lowercase() -> value
            tx.from?.hash?.lowercase() -> -value
            else -> return null
        }
        return ChainTransaction(network.id, tx.transactionHash, parseTime(tx.timestamp), network.nativeAsset, delta, null, false, true)
    }

    private fun tokenTransaction(network: EvmNetwork, transfer: BsTokenTransfer, own: String): ChainTransaction? {
        val hash = transfer.hash ?: return null
        val asset = transfer.token?.let { tokenAsset(network, it) } ?: return null
        val value = BigInteger.parseString(transfer.total?.value ?: "0")
        val incoming = transfer.to?.hash?.lowercase() == own
        val outgoing = transfer.from?.hash?.lowercase() == own
        val delta = when {
            incoming && outgoing -> BigInteger.ZERO
            incoming -> value
            outgoing -> -value
            else -> return null
        }
        return ChainTransaction(network.id, hash, parseTime(transfer.timestamp), asset, delta, null, incoming && outgoing, true)
    }

    /**
     * Polygon staking on Ethereum: delegators hold "Delegated POL #<validator hex>" share tokens.
     * The share contract is verified with the StakeManager (symbols can be copied by spam) and
     * converted with `getTotalStake`, which returns the exact POL amount (rewards excluded).
     */
    private suspend fun polygonStake(network: EvmNetwork, token: BsToken, address: String): AssetAmount? {
        if (network.chainId != 1L) return null
        val contract = token.contract?.lowercase() ?: return null
        val name = token.name.orEmpty()
        if (!name.startsWith("Delegated POL #") && !name.startsWith("Delegated MATIC #")) return null
        val validatorId = name.substringAfter('#').trim().toLongOrNull(16) ?: return null
        // RPC failures propagate (and fail this network's sync): falling back to the raw share
        // token would store a wrong amount under the same asset id.
        val registered = rpc.call(network.rpcUrls, POLYGON_STAKE_MANAGER, GET_VALIDATOR_CONTRACT + validatorId.toString(16).padStart(64, '0'))
        if (!registered.lowercase().endsWith(contract.removePrefix("0x"))) return null
        val stake = rpc.call(network.rpcUrls, contract, GET_TOTAL_STAKE + address.removePrefix("0x").lowercase().padStart(64, '0'))
        val amount = parseHex(stake.take(64))
        val asset = Asset(
            id = AssetId.token(network.id, contract),
            chainId = network.id,
            symbol = "POL",
            name = "POL (staked, validator $validatorId)",
            decimals = 18,
            priceId = POL_PRICE_ID,
            contract = contract,
        )
        return AssetAmount(asset, TokenAmount(amount, 18))
    }

    private fun tokenAsset(network: EvmNetwork, token: BsToken): Asset? {
        val contract = token.contract?.lowercase() ?: return null
        val decimals = token.decimals?.toIntOrNull() ?: return null
        return Asset(
            id = AssetId.token(network.id, contract),
            chainId = network.id,
            symbol = token.symbol?.take(MAX_SYMBOL) ?: "?",
            name = token.name?.take(MAX_NAME) ?: contract,
            decimals = decimals,
            priceId = network.coingeckoPlatform?.let { TokenPriceIds.forContract(it, contract) },
            contract = contract,
        )
    }

    private fun parseTime(value: String?): Instant =
        value?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Clock.System.now()

    private companion object {
        val DISCOVERY_INTERVAL = 24.hours

        /** Paging continues in the next sync afterwards, so a long history cannot hold up a refresh. */
        val HISTORY_BUDGET = 60.seconds
        val COUNTERS_TIMEOUT = 6.seconds

        /** Together below the 10 s limit of the activity check when adding a wallet. */
        val PROBE_NETWORK_TIMEOUT = 9.seconds
        const val POLYGON_STAKE_MANAGER = "0x5e3Ef299fDDf15eAa0432E6e66473ace8c13D908"
        const val GET_VALIDATOR_CONTRACT = "0x56342d8c" // getValidatorContract(uint256)
        const val GET_TOTAL_STAKE = "0x1e7ff8f6" // getTotalStake(address)
        const val POL_PRICE_ID = "polygon-ecosystem-token"

        /** Pages of each list a sync reads looking for known transactions (about 1000 rows). */
        const val MAX_HEAD_PAGES = 20

        /** Pages of each list a sync reads further down an unfinished history. */
        const val CONTINUATION_PAGES = 10
        const val MAX_SYMBOL = 16
        const val MAX_NAME = 64
    }
}

/** Price ids for tokens priced by contract address: `token:<coingecko platform>:<contract>`. */
object TokenPriceIds {
    private const val PREFIX = "token:"

    fun forContract(platform: String, contract: String) = "$PREFIX$platform:${contract.lowercase()}"

    fun parse(priceId: String): Pair<String, String>? =
        if (priceId.startsWith(PREFIX)) priceId.removePrefix(PREFIX).split(':', limit = 2).let { it[0] to it[1] } else null
}

/**
 * Heuristic spam detection for airdropped tokens. Spam tokens are hidden by default; the user
 * can unhide them.
 */
object SpamFilter {
    private val PATTERNS = Regex(
        """(https?://|www\.|\.com|\.io|\.org|\.net|\.xyz|\.app|t\.me|claim|reward|visit|airdrop|gift|bonus|voucher|\$\s?\d)""",
        RegexOption.IGNORE_CASE,
    )

    fun isSpam(symbol: String, name: String): Boolean =
        PATTERNS.containsMatchIn(symbol) || PATTERNS.containsMatchIn(name) || symbol.any { it.code > 0x7F }
}

/** Convenience: the chain id under which EVM wallets are stored. */
val EVM_WALLET_CHAIN: ChainId = ChainId.evm(1)
