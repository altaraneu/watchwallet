package com.altaran.watchwallet.chain.evm

import com.altaran.watchwallet.core.network.HostHealth
import com.altaran.watchwallet.core.network.HostThrottles
import com.altaran.watchwallet.core.network.pathSegment
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

// ---------------------------------------------------------------- JSON-RPC

/** `jsonrpc` and `id` must always be sent: the shared Json config does not encode defaults. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class RpcRequest(
    @EncodeDefault val jsonrpc: String = "2.0",
    @EncodeDefault val id: Int = 1,
    val method: String,
    val params: List<String>,
)

@Serializable
private data class RpcResponse(val result: String? = null, val error: JsonElement? = null)

/**
 * JSON-RPC over the hosts of a network. A host that fails, or answers with a JSON-RPC error (public
 * nodes report rate limits that way), is left for the next one; it is skipped for a while after.
 */
internal class EvmRpcClient(private val http: HttpClient, private val health: HostHealth) {
    suspend fun balance(rpcUrls: List<String>, address: String): BigInteger = hexCall(rpcUrls, "eth_getBalance", address)

    suspend fun transactionCount(rpcUrls: List<String>, address: String): BigInteger = hexCall(rpcUrls, "eth_getTransactionCount", address)

    /** `eth_call` of [data] on contract [to]; returns the raw hex result without `0x`. */
    suspend fun call(rpcUrls: List<String>, to: String, data: String): String = health.firstAvailable(rpcUrls) { url ->
        val response: JsonObject = http.post(url) {
            timeout { requestTimeoutMillis = RPC_TIMEOUT_MS }
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", "eth_call")
                    put(
                        "params",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("to", to)
                                    put("data", data)
                                },
                            )
                            add(JsonPrimitive("latest"))
                        },
                    )
                },
            )
        }.body()
        val result = response["result"]?.jsonPrimitive?.contentOrNull ?: error("eth_call failed")
        result.removePrefix("0x")
    }

    private suspend fun hexCall(rpcUrls: List<String>, method: String, address: String): BigInteger = health.firstAvailable(rpcUrls) { url ->
        val response: RpcResponse = http.post(url) {
            timeout { requestTimeoutMillis = RPC_TIMEOUT_MS }
            contentType(ContentType.Application.Json)
            setBody(RpcRequest(method = method, params = listOf(address, "latest")))
        }.body()
        parseHex(response.result ?: error("RPC $method failed"))
    }

    private companion object {
        /** Short, so that the alternative host is reached within the deadline of the network. */
        const val RPC_TIMEOUT_MS = 8_000L
    }
}

internal fun parseHex(hex: String): BigInteger {
    val body = hex.removePrefix("0x").ifEmpty { "0" }
    return BigInteger.parseString(body, 16)
}

// ---------------------------------------------------------------- Blockscout v2

@Serializable
internal data class BsCounters(
    @SerialName("transactions_count") val transactions: String? = null,
    @SerialName("token_transfers_count") val tokenTransfers: String? = null,
)

@Serializable
internal data class BsToken(
    @SerialName("address_hash") val addressHash: String? = null,
    val address: String? = null,
    val symbol: String? = null,
    val name: String? = null,
    val decimals: String? = null,
    val type: String? = null,
    @SerialName("exchange_rate") val exchangeRate: String? = null,
) {
    val contract: String? get() = addressHash ?: address
}

@Serializable
internal data class BsTokenBalance(val token: BsToken, val value: String? = null)

@Serializable
internal data class BsAddressRef(val hash: String? = null)

@Serializable
internal data class BsFee(val value: String? = null)

@Serializable
internal data class BsTransaction(
    val hash: String,
    val timestamp: String? = null,
    val from: BsAddressRef? = null,
    val to: BsAddressRef? = null,
    val value: String? = null,
    val fee: BsFee? = null,
    val status: String? = null,
    val result: String? = null,
)

@Serializable
internal data class BsInternalTransaction(
    @SerialName("transaction_hash") val transactionHash: String,
    val timestamp: String? = null,
    val from: BsAddressRef? = null,
    val to: BsAddressRef? = null,
    val value: String? = null,
    val success: Boolean? = null,
)

@Serializable
internal data class BsTotal(val value: String? = null, val decimals: String? = null)

@Serializable
internal data class BsTokenTransfer(
    @SerialName("transaction_hash") val transactionHash: String? = null,
    @SerialName("tx_hash") val txHash: String? = null,
    val timestamp: String? = null,
    val from: BsAddressRef? = null,
    val to: BsAddressRef? = null,
    val total: BsTotal? = null,
    val token: BsToken? = null,
) {
    val hash: String? get() = transactionHash ?: txHash
}

@Serializable
internal data class BsPage<T>(
    val items: List<T> = emptyList(),
    @SerialName("next_page_params") val nextPageParams: JsonObject? = null,
)

/**
 * Blockscout v2 over the instances of a network. Throttled per host: a slow instance never delays
 * the others. A blocked host (HTTP 403) or an outage fails the call at once for a while, and the
 * network keeps its last known tokens and history.
 */
internal class BlockscoutClient(private val http: HttpClient, private val health: HostHealth) {
    // Blockscout public instances allow a few requests per second per IP.
    private val throttles = HostThrottles(maxConcurrent = 4, minInterval = 150.milliseconds)

    suspend fun counters(hosts: List<String>, address: String): BsCounters =
        request(hosts) { base -> http.get("$base/api/v2/addresses/${pathSegment(address)}/counters").body() }

    suspend fun tokenBalances(hosts: List<String>, address: String): List<BsTokenBalance> =
        request(hosts) { base -> http.get("$base/api/v2/addresses/${pathSegment(address)}/token-balances").body() }

    suspend fun transactions(hosts: List<String>, address: String, next: JsonObject?): BsPage<BsTransaction> =
        page(hosts, "api/v2/addresses/${pathSegment(address)}/transactions", next)

    suspend fun internalTransactions(hosts: List<String>, address: String, next: JsonObject?): BsPage<BsInternalTransaction> =
        page(hosts, "api/v2/addresses/${pathSegment(address)}/internal-transactions", next)

    suspend fun tokenTransfers(hosts: List<String>, address: String, next: JsonObject?): BsPage<BsTokenTransfer> =
        page(hosts, "api/v2/addresses/${pathSegment(address)}/token-transfers", next, mapOf("type" to "ERC-20"))

    private suspend fun <T> request(hosts: List<String>, block: suspend (String) -> T): T =
        health.firstAvailable(hosts) { base -> throttles.run(base) { block(base) } }

    private suspend inline fun <reified T> page(hosts: List<String>, path: String, next: JsonObject?, extra: Map<String, String> = emptyMap()): BsPage<T> =
        request(hosts) { base ->
            http.get("$base/$path") {
                extra.forEach { (k, v) -> parameter(k, v) }
                next?.forEach { (key, value) ->
                    val text = (value as? JsonPrimitive)?.takeIf { it.isString || it.jsonPrimitive.content != "null" }?.content
                    if (text != null) parameter(key, text)
                }
            }.body()
        }
}
