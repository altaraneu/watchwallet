package com.altaran.watchwallet.chain.cosmos

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.Bech32Codec
import com.altaran.watchwallet.chain.common.accountTransaction
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.arr
import com.altaran.watchwallet.chain.common.at
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.content
import com.altaran.watchwallet.chain.common.parseBigInteger
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.chain.common.tokenAsset
import com.altaran.watchwallet.chain.common.withRateLimitBackoff
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** One Cosmos SDK chain reachable through cosmos.directory's REST proxy. */
data class CosmosNetwork(
    /** cosmos.directory chain name, also used in the [ChainId] (`cosmos:<name>`). */
    val name: String,
    val displayName: String,
    val hrp: String,
    val denom: String,
    val symbol: String,
    val decimals: Int,
    val coingeckoId: String?,
    /** SLIP-44 coin type; addresses convert between chains only when it matches (118). */
    val coinType: Int,
    val explorer: String,
    val color: Long,
) {
    val id: ChainId get() = ChainId("cosmos:$name")

    val chain: Chain
        get() = Chain(
            id = id,
            family = ChainFamily.COSMOS,
            name = displayName,
            nativeSymbol = symbol,
            nativeDecimals = decimals,
            nativePriceId = coingeckoId,
            explorerTxUrl = "$explorer/tx/{tx}",
            explorerAddressUrl = "$explorer/address/{address}",
            color = color,
        )

    val nativeAsset: Asset get() = Asset(AssetId.native(id), id, symbol, displayName, decimals, coingeckoId)

    /**
     * Delegated + unbonding native tokens, shown as a separate asset with the native price so
     * the portfolio value is complete while the liquid balance stays exact.
     */
    val stakedAsset: Asset get() = tokenAsset(id, "staked-$denom", symbol, "$displayName (staked)", decimals, coingeckoId)
}

object CosmosChains {
    val COSMOS_HUB = CosmosNetwork("cosmoshub", "Cosmos Hub", "cosmos", "uatom", "ATOM", 6, "cosmos", 118, "https://www.mintscan.io/cosmos", 0xFF2E3148)
    val OSMOSIS = CosmosNetwork("osmosis", "Osmosis", "osmo", "uosmo", "OSMO", 6, "osmosis", 118, "https://www.mintscan.io/osmosis", 0xFF750BBB)
    val CELESTIA = CosmosNetwork("celestia", "Celestia", "celestia", "utia", "TIA", 6, "celestia", 118, "https://www.mintscan.io/celestia", 0xFF7B2BF9)
    val INJECTIVE = CosmosNetwork("injective", "Injective", "inj", "inj", "INJ", 18, "injective-protocol", 60, "https://www.mintscan.io/injective", 0xFF0082FA)

    val ALL = listOf(COSMOS_HUB, OSMOSIS, CELESTIA, INJECTIVE)

    fun byHrp(hrp: String): CosmosNetwork? = ALL.firstOrNull { it.hrp == hrp }

    fun byId(id: ChainId): CosmosNetwork? = ALL.firstOrNull { it.id == id }

    const val DEFAULT_REST_URL = "https://rest.cosmos.directory"
}

/** Bech32 account addresses of the supported Cosmos chains (20-byte keys, 32-byte module/ICA accounts). */
object CosmosAddresses {
    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        val (hrp, bytes) = Bech32Codec.decodeBytes(text) ?: return null
        val network = CosmosChains.byHrp(hrp) ?: return null
        if (bytes.size != 20 && bytes.size != 32) return null
        return WatchTarget.Address(ChainFamily.COSMOS, network.id, text.lowercase())
    }

    /**
     * The same account on the other supported chains with coin type 118 (same key, different
     * prefix). Injective (coin type 60) derives a different key and is never offered.
     */
    fun relatedAddresses(address: String): List<WatchTarget.Address> {
        val (hrp, bytes) = Bech32Codec.decodeBytes(address.trim()) ?: return emptyList()
        val source = CosmosChains.byHrp(hrp) ?: return emptyList()
        if (source.coinType != 118 || bytes.size != 20) return emptyList()
        return CosmosChains.ALL.filter { it != source && it.coinType == 118 }
            .map { WatchTarget.Address(ChainFamily.COSMOS, it.id, Bech32Codec.encodeBytes(it.hrp, bytes)) }
    }
}

/**
 * Cosmos SDK chains via the cosmos.directory REST (LCD) proxy: liquid bank balance of the native
 * denom, delegated + unbonding stake as a separate "staked" asset, and best-effort history from
 * the tx search endpoint (public nodes often disable or time out on it; a failure only marks the
 * history as truncated). IBC and factory tokens are not reported yet.
 */
class CosmosAdapter(
    private val http: HttpClient,
    private val restUrl: String = CosmosChains.DEFAULT_REST_URL,
    networks: List<CosmosNetwork> = CosmosChains.ALL,
) : ChainAdapter {
    private val throttle = Throttle(maxConcurrent = 2, minInterval = 200.milliseconds, maxJitter = 100.milliseconds)

    override val family = ChainFamily.COSMOS
    override val chains = networks.map { it.chain }

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("Cosmos REST") {
        val (network, address) = resolve(target)
        val balances = balances(network, address)
        val active = balances.any { !it.amount.isZero } || accountExists(network, address)
        ProbeResult(active, if (active) listOf(network.id) else emptyList(), balances, transactionCount = null)
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("Cosmos REST") {
        val (network, address) = resolve(target)
        val balances = balances(network, address)
        var truncated = false
        val transactions = try {
            history(network, address, knownTransactions)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            truncated = true
            emptyList()
        }
        val active = balances.any { !it.amount.isZero } || transactions.isNotEmpty()
        SyncResult(balances, transactions, activeChains = if (active) listOf(network.id) else emptyList(), historyTruncated = truncated)
    }

    private fun resolve(target: WatchTarget): Pair<CosmosNetwork, String> {
        val address = (target as? WatchTarget.Address)?.address ?: throw ChainDataException("Cosmos supports addresses only")
        val hrp = Bech32Codec.decodeBytes(address)?.first
        val network = CosmosChains.byId((target as WatchTarget.Address).chainId) ?: hrp?.let { CosmosChains.byHrp(it) }
            ?: throw ChainDataException("Unsupported Cosmos chain")
        return network to address
    }

    private fun base(network: CosmosNetwork) = "${restUrl.trimEnd('/')}/${network.name}"

    private suspend fun balances(network: CosmosNetwork, address: String): List<AssetAmount> {
        val base = base(network)
        val bank = get("$base/cosmos/bank/v1beta1/balances/$address") { parameter("pagination.limit", 500) }
        val liquid = bank.arr("balances").firstOrNull { it.str("denom") == network.denom }?.str("amount")
        val delegations = get("$base/cosmos/staking/v1beta1/delegations/$address") { parameter("pagination.limit", 500) }
        var staked = delegations.arr("delegation_responses")
            .filter { it.at("balance").str("denom") == network.denom }
            .fold(BigInteger.ZERO) { sum, it -> sum + parseBigInteger(it.at("balance").str("amount")) }
        val unbonding = get("$base/cosmos/staking/v1beta1/delegators/$address/unbonding_delegations")
        unbonding.arr("unbonding_responses").forEach { response ->
            response.arr("entries").forEach { staked += parseBigInteger(it.str("balance")) }
        }
        val result = mutableListOf(amountOf(network.nativeAsset, liquid))
        if (!staked.isZero()) result += amountOf(network.stakedAsset, staked)
        return result
    }

    private suspend fun accountExists(network: CosmosNetwork, address: String): Boolean = try {
        get("${base(network)}/cosmos/auth/v1beta1/accounts/$address")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        false
    }

    /** Sent and received transactions (two event queries), newest first, merged by hash. */
    private suspend fun history(network: CosmosNetwork, address: String, known: Set<String>): List<ChainTransaction> {
        val responses = LinkedHashMap<String, JsonElement>()
        for (query in listOf("message.sender='$address'", "transfer.recipient='$address'")) {
            val page = get("${base(network)}/cosmos/tx/v1beta1/txs") {
                // SDK >= 0.50 uses `query`, older versions `events`; send both.
                parameter("query", query)
                parameter("events", query)
                parameter("order_by", "ORDER_BY_DESC")
                parameter("pagination.limit", PAGE_SIZE)
                parameter("limit", PAGE_SIZE)
                parameter("page", 1)
            }
            for (response in page.arr("tx_responses")) {
                val hash = response.str("txhash") ?: continue
                if (hash in known) break
                responses.getOrPut(hash) { response }
            }
        }
        return responses.values.mapNotNull { parseCosmosTransaction(network, it, address) }
    }

    private suspend fun get(url: String, block: HttpRequestBuilder.() -> Unit = {}): JsonElement =
        withRateLimitBackoff(RATE_LIMIT_RETRIES) { throttle.run { http.get(url, block).body<JsonElement>() } }

    private companion object {
        const val PAGE_SIZE = 50
        const val RATE_LIMIT_RETRIES = 2
    }
}

private val COIN = Regex("""^(\d+)([a-zA-Z][a-zA-Z0-9/:._-]*)$""")

/** Sum of [denom] in a coin list such as `1000uatom,5ibc/27…`. */
internal fun coinAmount(coins: String?, denom: String): BigInteger =
    coins.orEmpty().split(',').mapNotNull { COIN.matchEntire(it.trim()) }
        .filter { it.groupValues[2] == denom }
        .fold(BigInteger.ZERO) { sum, match -> sum + parseBigInteger(match.groupValues[1]) }

/**
 * Native-denom delta of a tx for [address] from `coin_received` / `coin_spent` events (the fee
 * deduction is a `coin_spent` of the fee payer, so the fee is included).
 */
internal fun parseCosmosTransaction(network: CosmosNetwork, response: JsonElement, address: String): ChainTransaction? {
    val hash = response.str("txhash") ?: return null
    var delta = BigInteger.ZERO
    var touched = false
    for (event in response.arr("events")) {
        val type = event.str("type")
        if (type != "coin_received" && type != "coin_spent") continue
        // Attributes come in groups: receiver/spender followed by amount.
        var current: String? = null
        for (attribute in event.arr("attributes")) {
            when (attribute.str("key")) {
                "receiver", "spender" -> current = attribute.str("value")
                "amount" -> if (current == address) {
                    val amount = coinAmount(attribute.str("value"), network.denom)
                    delta = if (type == "coin_received") delta + amount else delta - amount
                    touched = true
                }
            }
        }
    }
    if (!touched) return null
    val time = response.str("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Clock.System.now()
    val feePayer = response.at("tx", "auth_info", "fee").str("payer").let { it.isNullOrEmpty() } &&
        response.at("tx", "body").arr("messages").firstOrNull().let { msg ->
            msg.str("from_address") == address || msg.str("delegator_address") == address || msg.str("sender") == address
        }
    val fee = if (feePayer) {
        response.at("tx", "auth_info", "fee").arr("amount").filter { it.str("denom") == network.denom }
            .fold(BigInteger.ZERO) { sum, it -> sum + parseBigInteger(it.str("amount")) }
    } else {
        null
    }
    // Failed transactions still charge the fee; their events only contain the fee deduction.
    return accountTransaction(network.id, hash, time, network.nativeAsset, delta, fee)
}
