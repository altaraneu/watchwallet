package com.altaran.watchwallet.chain.evm

import com.altaran.watchwallet.core.network.Throttle
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------- JSON-RPC

@Serializable
private data class RpcRequest(val jsonrpc: String = "2.0", val id: Int = 1, val method: String, val params: List<String>)

@Serializable
private data class RpcResponse(val result: String? = null, val error: JsonElement? = null)

internal class EvmRpcClient(private val http: HttpClient) {
    suspend fun balance(rpcUrl: String, address: String): BigInteger = hexCall(rpcUrl, "eth_getBalance", address)

    suspend fun transactionCount(rpcUrl: String, address: String): BigInteger = hexCall(rpcUrl, "eth_getTransactionCount", address)

    private suspend fun hexCall(rpcUrl: String, method: String, address: String): BigInteger {
        val response: RpcResponse = http.post(rpcUrl) {
            contentType(ContentType.Application.Json)
            setBody(RpcRequest(method = method, params = listOf(address, "latest")))
        }.body()
        val hex = response.result ?: error("RPC $method failed: ${response.error}")
        return parseHex(hex)
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

internal class BlockscoutClient(private val http: HttpClient) {
    // Blockscout public instances allow a few requests per second per IP.
    private val throttle = Throttle(maxConcurrent = 4, minInterval = kotlin.time.Duration.parse("150ms"))

    suspend fun counters(base: String, address: String): BsCounters =
        throttle.run { http.get("$base/api/v2/addresses/$address/counters").body() }

    suspend fun tokenBalances(base: String, address: String): List<BsTokenBalance> =
        throttle.run { http.get("$base/api/v2/addresses/$address/token-balances").body() }

    suspend fun transactions(base: String, address: String, next: JsonObject?): BsPage<BsTransaction> =
        page("$base/api/v2/addresses/$address/transactions", next)

    suspend fun internalTransactions(base: String, address: String, next: JsonObject?): BsPage<BsInternalTransaction> =
        page("$base/api/v2/addresses/$address/internal-transactions", next)

    suspend fun tokenTransfers(base: String, address: String, next: JsonObject?): BsPage<BsTokenTransfer> =
        page("$base/api/v2/addresses/$address/token-transfers", next, mapOf("type" to "ERC-20"))

    private suspend inline fun <reified T> page(url: String, next: JsonObject?, extra: Map<String, String> = emptyMap()): BsPage<T> =
        throttle.run {
            http.get(url) {
                extra.forEach { (k, v) -> parameter(k, v) }
                next?.forEach { (key, value) ->
                    val text = (value as? JsonPrimitive)?.takeIf { it.isString || it.jsonPrimitive.content != "null" }?.content
                    if (text != null) parameter(key, text)
                }
            }.body()
        }
}
