package com.altaran.watchwallet.chain.evm

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.WatchTarget
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One `0x` address scanned across all enabled EVM networks in parallel.
 *
 * Native balance and activity come from public JSON-RPC; tokens and history from Blockscout
 * where the network has an instance.
 */
class EvmAdapter(
    http: HttpClient,
    private val networks: List<EvmNetwork> = EvmNetworks.DEFAULT,
) : ChainAdapter {
    private val rpc = EvmRpcClient(http)
    private val blockscout = BlockscoutClient(http)

    override val family = ChainFamily.EVM
    override val chains = networks.map { it.chain }

    /**
     * Activity of the address on one network. [conclusive] is `false` when the indexer could not
     * be asked (receive-only addresses may then look inactive although they hold tokens).
     */
    private data class NetworkActivity(
        val network: EvmNetwork,
        val nativeBalance: BigInteger,
        val active: Boolean,
        val txCount: Int,
        val conclusive: Boolean = true,
    )

    override suspend fun probe(target: WatchTarget): ProbeResult {
        val address = (target as? WatchTarget.Address)?.address ?: throw ChainDataException("EVM supports addresses only")
        val activity = scanNetworks(address)
        val active = activity.filter { it.active }
        return ProbeResult(
            hasActivity = active.isNotEmpty(),
            activeChains = active.map { it.network.id },
            balances = active.filter { !it.nativeBalance.isZero() }.map {
                AssetAmount(it.network.nativeAsset, TokenAmount(it.nativeBalance, 18))
            },
            transactionCount = active.sumOf { it.txCount },
        )
    }

    /**
     * Networks that cannot be reached, or whose balances or history fail to load, are left out of
     * [SyncResult.syncedChains], so their stored balances and history are kept as they are instead
     * of being replaced by an incomplete picture.
     */
    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = coroutineScope {
        val address = (target as? WatchTarget.Address)?.address ?: throw ChainDataException("EVM supports addresses only")
        val scanned = scanNetworks(address)
        val activity = scanned.filter { it.active }
        val perNetwork = activity.map { act ->
            async {
                // A failing network must not cancel the others (the inner scope keeps the failure local).
                act to runCatching { coroutineScope { syncNetwork(act, address, knownTransactions) } }
            }
        }.awaitAll()
        perNetwork.forEach { (_, result) ->
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
        }
        val synced = perNetwork.mapNotNull { (act, result) -> result.getOrNull()?.let { act to it } }
        if (activity.isNotEmpty() && synced.isEmpty()) {
            val cause = perNetwork.firstNotNullOfOrNull { it.second.exceptionOrNull() }
            throw ChainDataException("No EVM network could be synced: ${cause?.message}", cause)
        }
        val failed = perNetwork.filter { it.second.isFailure }.map { it.first.network.id }
        SyncResult(
            balances = synced.flatMap { it.second.balances },
            transactions = synced.flatMap { it.second.transactions },
            // Failed networks stay active: they are retried by the next sync.
            activeChains = activity.map { it.network.id },
            historyTruncated = synced.any { it.second.historyTruncated },
            // Inactive networks are known to hold nothing only when the indexer confirmed it.
            syncedChains = scanned.filter { it.active || it.conclusive }.map { it.network.id } - failed.toSet(),
        )
    }

    /** Checks every network in parallel; unreachable networks are skipped, not fatal. */
    private suspend fun scanNetworks(address: String): List<NetworkActivity> = coroutineScope {
        val results = networks.map { network ->
            async {
                // Fast path: balance and nonce over RPC decide activity for almost every address.
                val rpcResult = withTimeoutOrNull(RPC_TIMEOUT) {
                    // The inner scope keeps a failing request (e.g. a dead RPC host) from cancelling the
                    // whole scan: the exception surfaces here and the network is skipped.
                    runCatching {
                        coroutineScope {
                            val balance = async { rpc.balance(network.rpcUrl, address) }
                            val nonce = async { rpc.transactionCount(network.rpcUrl, address) }
                            balance.await() to nonce.await()
                        }
                    }.getOrNull()
                } ?: return@async null
                val (balance, nonce) = rpcResult
                if (!balance.isZero() || !nonce.isZero()) {
                    return@async NetworkActivity(network, balance, true, nonce.intValue(exactRequired = false))
                }
                // Receive-only addresses (e.g. airdropped tokens) have zero nonce and native balance:
                // ask the indexer, with its own short timeout (counters can be slow for big accounts).
                val base = network.blockscoutUrl ?: return@async NetworkActivity(network, balance, false, 0)
                val counters = withTimeoutOrNull(INDEXER_TIMEOUT) {
                    runCatching { coroutineScope { blockscout.counters(base, address) } }.getOrNull()
                }
                val txCount = (counters?.transactions?.toIntOrNull() ?: 0) + (counters?.tokenTransfers?.toIntOrNull() ?: 0)
                NetworkActivity(network, balance, txCount > 0, txCount, conclusive = counters != null)
            }
        }.awaitAll()
        if (results.all { it == null }) throw ChainDataException("No EVM network reachable")
        results.filterNotNull()
    }

    private suspend fun syncNetwork(activity: NetworkActivity, address: String, known: Set<String>): SyncResult {
        val network = activity.network
        val balances = mutableListOf(AssetAmount(network.nativeAsset, TokenAmount(activity.nativeBalance, 18)))
        val base = network.blockscoutUrl ?: return SyncResult(balances, emptyList())

        // A failure here fails the network (its stored balances are kept), never "no tokens".
        val tokenBalances = blockscout.tokenBalances(base, address)
        tokenBalances.filter { it.token.type == "ERC-20" && it.token.contract != null }.forEach { balance ->
            // Staked POL is held as validator share tokens: report the POL they represent.
            polygonStake(network, balance.token, address)?.let {
                balances += it
                return@forEach
            }
            val asset = tokenAsset(network, balance.token) ?: return@forEach
            balances += AssetAmount(asset, TokenAmount.parseRaw(balance.value ?: "0", asset.decimals))
        }

        var truncated = false
        val transactions = mutableListOf<ChainTransaction>()
        val own = address.lowercase()

        val native = collectPages(known) { next -> blockscout.transactions(base, address, next) }
        truncated = truncated || native.second
        native.first.forEach { tx -> nativeTransaction(network, tx, own)?.let(transactions::add) }

        val internal = collectPages(known, { it.transactionHash }) { next -> blockscout.internalTransactions(base, address, next) }
        truncated = truncated || internal.second
        internal.first.forEach { tx -> internalTransaction(network, tx, own)?.let(transactions::add) }

        val tokens = collectPages(known, { it.hash }) { next -> blockscout.tokenTransfers(base, address, next) }
        truncated = truncated || tokens.second
        tokens.first.forEach { transfer -> tokenTransaction(network, transfer, own)?.let(transactions::add) }

        // Several rows for the same (hash, asset) — e.g. a swap with internal value — are merged.
        val merged = transactions.groupBy { it.hash to it.asset.id }.map { (_, rows) ->
            rows.reduce { acc, row -> acc.copy(delta = acc.delta + row.delta, fee = acc.fee ?: row.fee) }
        }
        return SyncResult(balances, merged, historyTruncated = truncated)
    }

    private suspend fun <T> collectPages(
        known: Set<String>,
        hashOf: (T) -> String? = { (it as? BsTransaction)?.hash },
        fetch: suspend (JsonObject?) -> BsPage<T>,
    ): Pair<List<T>, Boolean> {
        val items = mutableListOf<T>()
        var next: JsonObject? = null
        repeat(MAX_PAGES) {
            // Errors propagate: returning a partial page set would leave a permanent gap, because the
            // next sync stops at the transactions stored now.
            val page = fetch(next)
            items += page.items
            if (page.nextPageParams == null || page.items.any { hashOf(it) in known }) return items to false
            next = page.nextPageParams
        }
        return items to true
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
        val registered = rpc.call(network.rpcUrl, POLYGON_STAKE_MANAGER, GET_VALIDATOR_CONTRACT + validatorId.toString(16).padStart(64, '0'))
        if (!registered.lowercase().endsWith(contract.removePrefix("0x"))) return null
        val stake = rpc.call(network.rpcUrl, contract, GET_TOTAL_STAKE + address.removePrefix("0x").lowercase().padStart(64, '0'))
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
        val RPC_TIMEOUT = 10.seconds
        const val POLYGON_STAKE_MANAGER = "0x5e3Ef299fDDf15eAa0432E6e66473ace8c13D908"
        const val GET_VALIDATOR_CONTRACT = "0x56342d8c" // getValidatorContract(uint256)
        const val GET_TOTAL_STAKE = "0x1e7ff8f6" // getTotalStake(address)
        const val POL_PRICE_ID = "polygon-ecosystem-token"
        val INDEXER_TIMEOUT = 6.seconds
        const val MAX_PAGES = 20
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
