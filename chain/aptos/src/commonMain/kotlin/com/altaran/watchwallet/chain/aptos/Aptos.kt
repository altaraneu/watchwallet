package com.altaran.watchwallet.chain.aptos

import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.accountTransaction
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.arr
import com.altaran.watchwallet.chain.common.asList
import com.altaran.watchwallet.chain.common.at
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.content
import com.altaran.watchwallet.chain.common.long
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.notFoundAsNull
import com.altaran.watchwallet.chain.common.parseBigInteger
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.core.endpoints.BundledEndpoints
import com.altaran.watchwallet.core.endpoints.EndpointProtocol
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object AptosChain {
    val ID = ChainId("aptos")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.APTOS,
        name = "Aptos",
        nativeSymbol = "APT",
        nativeDecimals = 8,
        nativePriceId = "aptos",
        explorerTxUrl = "https://explorer.aptoslabs.com/txn/{tx}?network=mainnet",
        explorerAddressUrl = "https://explorer.aptoslabs.com/account/{address}?network=mainnet",
        color = 0xFF06F7F7,
    )

    val APT: Asset = nativeAsset(CHAIN)

    const val APT_COIN_TYPE = "0x1::aptos_coin::AptosCoin"
}

/**
 * Aptos account addresses: `0x` + 1–64 hex characters, normalized to the long form (64 lowercase
 * hex, left-padded). The format has no checksum; the 64-hex form is shared with Sui.
 */
object AptosAddresses {
    private val SHAPE = Regex("^0[xX][0-9a-fA-F]{1,64}$")

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        if (!SHAPE.matches(text)) return null
        return WatchTarget.Address(ChainFamily.APTOS, AptosChain.ID, normalize(text))
    }

    fun normalize(address: String): String = "0x" + address.trim().substring(2).lowercase().padStart(64, '0')
}

/**
 * Aptos via the public fullnode REST API.
 *
 * Limitations: only the APT balance is reported (fungible-asset/coin tokens are not listed), and
 * history contains only transactions **sent** by the account (the REST API has no keyless
 * "incoming transfers" query): each shows the gas fee plus the APT amount of the standard
 * transfer entry functions (`aptos_account::transfer`, `transfer_coins<AptosCoin>`,
 * `coin::transfer<AptosCoin>`, `aptos_account::batch_transfer`). Incoming transfers appear only
 * as balance changes.
 */
class AptosAdapter(
    private val http: HttpClient,
    apiUrl: String = BundledEndpoints.first(AptosChain.ID, EndpointProtocol.APTOS_REST),
) : ChainAdapter {
    private val base = apiUrl.trimEnd('/')
    private val throttle = Throttle(maxConcurrent = 3, minInterval = 100.milliseconds, maxJitter = 80.milliseconds)

    override val family = ChainFamily.APTOS
    override val chains = listOf(AptosChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("Aptos") {
        val address = address(target)
        val account = account(address)
        val balance = if (account != null) balance(address) else BigInteger.ZERO
        val sequence = account.long("sequence_number") ?: 0L
        val active = account != null && (sequence > 0 || !balance.isZero())
        ProbeResult(
            active,
            if (active) listOf(AptosChain.ID) else emptyList(),
            listOf(amountOf(AptosChain.APT, balance)),
            sequence.toInt(),
        )
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("Aptos") {
        val address = address(target)
        val account = account(address)
            ?: return@chainCall SyncResult(listOf(amountOf(AptosChain.APT, BigInteger.ZERO)), emptyList())
        val balances = listOf(amountOf(AptosChain.APT, balance(address)))

        // Sent transactions are indexed by sequence number; page backwards from the newest.
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES
        var end = account.long("sequence_number") ?: 0L
        val transactions = mutableListOf<ChainTransaction>()
        var truncated = false
        var pages = 0
        paging@ while (end > 0) {
            val start = maxOf(0L, end - PAGE_SIZE)
            val page = get("$base/accounts/$address/transactions") {
                parameter("start", start)
                parameter("limit", end - start)
            }.asList
            for (tx in page.reversed()) {
                val hash = tx.str("hash") ?: continue
                if (hash in knownTransactions) break@paging
                parseAptosTransaction(tx, address)?.let { transactions += it }
            }
            end = start
            if (++pages >= maxPages && end > 0) {
                truncated = true
                break
            }
        }
        val active = transactions.isNotEmpty() || !balances.first().amount.isZero
        SyncResult(
            balances,
            transactions,
            activeChains = if (active) listOf(AptosChain.ID) else emptyList(),
            historyTruncated = truncated,
        )
    }

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address?.let { AptosAddresses.normalize(it) }
            ?: throw ChainDataException("Aptos supports addresses only")

    private suspend fun account(address: String): JsonElement? = notFoundAsNull { get("$base/accounts/$address") }

    /** APT balance via the `0x1::coin::balance` view (covers coin + paired fungible asset). */
    private suspend fun balance(address: String): BigInteger {
        val coin = try {
            view("0x1::coin::balance", listOf(AptosChain.APT_COIN_TYPE), listOf(address))
        } catch (e: ResponseException) {
            if (e.response.status.value != 400) throw e
            null
        }
        val value =
            coin
                ?: view(
                    "0x1::primary_fungible_store::balance",
                    listOf("0x1::fungible_asset::Metadata"),
                    listOf(address, "0xa"),
                )
        return parseBigInteger(value.asList.firstOrNull().content)
    }

    private suspend fun view(
        function: String,
        typeArguments: List<String>,
        arguments: List<String>,
    ): JsonElement = throttle.run {
        http.post("$base/view") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("function", function)
                    putJsonArray("type_arguments") { typeArguments.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("arguments") { arguments.forEach { add(JsonPrimitive(it)) } }
                },
            )
        }.body<JsonElement>()
    }

    private suspend fun get(
        url: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
        },
    ): JsonElement =
        throttle.run { http.get(url, block).body<JsonElement>() }

    private companion object {
        const val PAGE_SIZE = 100L
        const val FIRST_SYNC_PAGES = 1
        const val MAX_PAGES = 20
    }
}

/** APT effect of a transaction sent by [address]: transfer amount (standard entry functions) plus gas. */
internal fun parseAptosTransaction(tx: JsonElement, address: String): ChainTransaction? {
    val hash = tx.str("hash") ?: return null
    if (tx.str("sender")?.let { AptosAddresses.normalize(it) } != address) return null
    val fee = parseBigInteger(tx.str("gas_used")) * parseBigInteger(tx.str("gas_unit_price"))
    val time = tx.long("timestamp")?.let { Instant.fromEpochMilliseconds(it / 1_000) } ?: Clock.System.now()
    val success = tx.str("success") != "false"

    var sent = BigInteger.ZERO
    var self = false
    if (success) {
        val payload = tx.at("payload")
        val function = payload.str("function")
        val typeArg = payload.arr("type_arguments").firstOrNull().content
        val args = payload.arr("arguments")
        fun transfer(to: String?, amount: String?) {
            if (to != null && AptosAddresses.normalize(to) == address) {
                self = true
            } else {
                sent += parseBigInteger(amount)
            }
        }
        when (function) {
            "0x1::aptos_account::transfer" -> {
                transfer(args.getOrNull(0).content, args.getOrNull(1).content)
            }

            "0x1::aptos_account::transfer_coins", "0x1::coin::transfer" -> {
                if (typeArg == AptosChain.APT_COIN_TYPE) transfer(args.getOrNull(0).content, args.getOrNull(1).content)
            }

            "0x1::aptos_account::batch_transfer" -> {
                val recipients = args.getOrNull(0).asList
                val amounts = args.getOrNull(1).asList
                recipients.forEachIndexed { i, to -> transfer(to.content, amounts.getOrNull(i).content) }
            }
        }
    }
    return accountTransaction(
        AptosChain.ID,
        hash,
        time,
        AptosChain.APT,
        BigInteger.ZERO - (sent + fee),
        fee,
        isSelfTransfer =
            self && sent.isZero(),
    )
}
