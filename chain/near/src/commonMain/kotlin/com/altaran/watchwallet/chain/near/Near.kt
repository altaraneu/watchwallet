package com.altaran.watchwallet.chain.near

import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.JsonRpcClient
import com.altaran.watchwallet.chain.common.RpcErrorException
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.parseBigInteger
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

object NearChain {
    val ID = ChainId("near")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.NEAR,
        name = "NEAR",
        nativeSymbol = "NEAR",
        nativeDecimals = 24,
        nativePriceId = "near",
        explorerTxUrl = "https://nearblocks.io/txns/{tx}",
        explorerAddressUrl = "https://nearblocks.io/address/{address}",
        color = 0xFF00EC97,
    )

    val NEAR: Asset = nativeAsset(CHAIN)

    const val DEFAULT_RPC_URL = "https://rpc.mainnet.near.org"
}

/**
 * NEAR account ids. NEAR has no checksum; ids are validated against the protocol rules
 * (2–64 chars, lowercase, `.`-separated parts of `[a-z0-9]` with single `-`/`_` separators).
 *
 * [parse] is deliberately strict to avoid matching ordinary words: it accepts implicit accounts
 * (64 lowercase hex; note that SecretGuard refuses bare 64-hex input as a possible private key),
 * ETH-implicit accounts (`0x` + 40 lowercase hex) and named accounts under a well-known
 * top-level account (`*.near`, `*.tg`, …). [parseAny] accepts every valid account id,
 * including top-level names such as `aurora`, for an explicit "NEAR account" input mode.
 */
object NearAddresses {
    private val ACCOUNT_ID = Regex("""^(([a-z\d]+[\-_])*[a-z\d]+\.)*([a-z\d]+[\-_])*[a-z\d]+$""")
    private val IMPLICIT = Regex("^[0-9a-f]{64}$")
    private val ETH_IMPLICIT = Regex("^0x[0-9a-f]{40}$")
    private val KNOWN_SUFFIXES = listOf(".near", ".tg", ".sweat", ".kaiching", ".game")

    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        val strict = IMPLICIT.matches(text) || ETH_IMPLICIT.matches(text) ||
            (KNOWN_SUFFIXES.any { text.endsWith(it) && text.length > it.length } && isValidAccountId(text))
        return if (strict) WatchTarget.Address(ChainFamily.NEAR, NearChain.ID, text) else null
    }

    fun parseAny(input: String): WatchTarget.Address? {
        val text = input.trim()
        val valid = ETH_IMPLICIT.matches(text) || isValidAccountId(text)
        return if (valid) WatchTarget.Address(ChainFamily.NEAR, NearChain.ID, text) else null
    }

    fun isValidAccountId(id: String): Boolean = id.length in 2..64 && ACCOUNT_ID.matches(id)

    fun isImplicit(id: String): Boolean = IMPLICIT.matches(id)
}

/**
 * NEAR via public JSON-RPC `view_account`. The reported balance is `amount + locked` (locked =
 * validator stake). Tokens delegated to staking pools live in the pool contracts and are not
 * included; NEP-141 fungible tokens are not listed.
 *
 * History is not available: NEAR RPC has no per-account transaction query and there is no
 * keyless public indexer, so [sync] returns balances only.
 */
class NearAdapter(
    http: HttpClient,
    rpcUrl: String = NearChain.DEFAULT_RPC_URL,
) : ChainAdapter {
    private val rpc = JsonRpcClient(http, listOf(rpcUrl), Throttle(maxConcurrent = 2, minInterval = 150.milliseconds), rateLimitRetries = 2)

    override val family = ChainFamily.NEAR
    override val chains = listOf(NearChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("NEAR") {
        val account = viewAccount(address(target))
        val balance = total(account)
        val active = account != null
        ProbeResult(active, if (active) listOf(NearChain.ID) else emptyList(), listOf(amountOf(NearChain.NEAR, balance)), transactionCount = null)
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("NEAR") {
        val account = viewAccount(address(target))
        SyncResult(
            balances = listOf(amountOf(NearChain.NEAR, total(account))),
            transactions = emptyList(),
            activeChains = if (account != null) listOf(NearChain.ID) else emptyList(),
        )
    }

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("NEAR supports accounts only")

    private fun total(account: JsonElement?): BigInteger =
        if (account == null) BigInteger.ZERO else parseBigInteger(account.str("amount")) + parseBigInteger(account.str("locked"))

    /** `view_account` result, or `null` when the account does not exist. */
    private suspend fun viewAccount(accountId: String): JsonElement? {
        val result = try {
            rpc.call(
                "query",
                buildJsonObject {
                    put("request_type", "view_account")
                    put("finality", "final")
                    put("account_id", accountId)
                },
            )
        } catch (e: RpcErrorException) {
            if ("UNKNOWN_ACCOUNT" in e.text || "does not exist" in e.text) return null
            throw e
        }
        // Older nodes report errors inside `result`.
        val inlineError = result.str("error")
        if (inlineError != null) {
            if ("does not exist" in inlineError || "UNKNOWN_ACCOUNT" in inlineError) return null
            throw ChainDataException("NEAR RPC error: $inlineError")
        }
        return result
    }
}
