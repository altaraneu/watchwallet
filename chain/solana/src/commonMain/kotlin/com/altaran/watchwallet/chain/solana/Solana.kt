package com.altaran.watchwallet.chain.solana

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.Base58Checks
import com.altaran.watchwallet.chain.common.JsonRpcClient
import com.altaran.watchwallet.chain.common.accountTransaction
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.arr
import com.altaran.watchwallet.chain.common.asList
import com.altaran.watchwallet.chain.common.at
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.content
import com.altaran.watchwallet.chain.common.long
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.obj
import com.altaran.watchwallet.chain.common.parseBigInteger
import com.altaran.watchwallet.chain.common.str
import com.altaran.watchwallet.chain.common.tokenAsset
import com.altaran.watchwallet.chain.common.tokenPriceId
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import com.ionspin.kotlin.bignum.integer.BigInteger
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object SolanaChain {
    val ID = ChainId("solana")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.SOLANA,
        name = "Solana",
        nativeSymbol = "SOL",
        nativeDecimals = 9,
        nativePriceId = "solana",
        explorerTxUrl = "https://solscan.io/tx/{tx}",
        explorerAddressUrl = "https://solscan.io/account/{address}",
        color = 0xFF9945FF,
    )

    val SOL: Asset = nativeAsset(CHAIN)

    const val DEFAULT_RPC_URL = "https://api.mainnet-beta.solana.com"
    const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"

    /** CoinGecko platform id for mint-based token prices. */
    const val COINGECKO_PLATFORM = "solana"
}

/** Solana addresses: Base58 encoding of 32 bytes (no checksum exists in the format). */
object SolanaAddresses {
    fun parse(input: String): WatchTarget.Address? {
        val text = input.trim()
        if (text.length !in 32..44) return null
        val bytes = Base58Checks.decodePlain(text) ?: return null
        if (bytes.size != 32) return null
        return WatchTarget.Address(ChainFamily.SOLANA, SolanaChain.ID, text)
    }
}

/** Well-known SPL mints: symbol, name, decimals and CoinGecko id (the RPC has no token metadata). */
internal object SolanaTokens {
    data class Info(val symbol: String, val name: String, val coingeckoId: String?)

    val KNOWN: Map<String, Info> = mapOf(
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to Info("USDC", "USD Coin", "usd-coin"),
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to Info("USDT", "Tether USD", "tether"),
        "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN" to Info("JUP", "Jupiter", "jupiter-exchange-solana"),
        "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263" to Info("BONK", "Bonk", "bonk"),
        "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm" to Info("WIF", "dogwifhat", "dogwifcoin"),
        "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So" to Info("mSOL", "Marinade staked SOL", "msol"),
        "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn" to Info("JitoSOL", "Jito Staked SOL", "jito-staked-sol"),
        "jtojtomepa8beP8AuQc6eXt5FriJwfFMwQx2v2f9mCL" to Info("JTO", "Jito", "jito-governance-token"),
        "HZ1JovNiVvGrGNiiYvEozEVgZ58xaU3RKwX8eACQBCt3" to Info("PYTH", "Pyth Network", "pyth-network"),
        "4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R" to Info("RAY", "Raydium", "raydium"),
        "So11111111111111111111111111111111111111112" to Info("wSOL", "Wrapped SOL", "wrapped-solana"),
        "2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo" to Info("PYUSD", "PayPal USD", "paypal-usd"),
        "7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs" to Info("WETH", "Wrapped Ether (Wormhole)", "ethereum-wormhole"),
        "bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1" to Info("bSOL", "BlazeStake Staked SOL", "blazestake-staked-sol"),
    )
}

/**
 * Solana via public JSON-RPC: SOL balance, SPL Token and Token-2022 balances, and history from
 * `getSignaturesForAddress` + `getTransaction`, whose pre/post balances give exact deltas
 * (fees included for the fee payer).
 *
 * Known limitations: stake accounts (delegated SOL) are separate accounts and not included;
 * token symbols are only known for a built-in list of major mints.
 */
class SolanaAdapter(
    http: HttpClient,
    rpcUrl: String = SolanaChain.DEFAULT_RPC_URL,
) : ChainAdapter {
    // The public endpoint allows roughly 100 requests per 10 s per IP and fewer for getTransaction.
    private val rpc = JsonRpcClient(
        http,
        listOf(rpcUrl),
        Throttle(maxConcurrent = 2, minInterval = 150.milliseconds, maxJitter = 100.milliseconds),
        rateLimitRetries = 3,
    )

    override val family = ChainFamily.SOLANA
    override val chains = listOf(SolanaChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("Solana") {
        val address = address(target)
        val lamports = balance(address)
        val signatures = signatures(address, before = null, limit = 1)
        val tokens = tokenBalances(address)
        val active = !lamports.isZero() || signatures.isNotEmpty() || tokens.isNotEmpty()
        ProbeResult(
            hasActivity = active,
            activeChains = if (active) listOf(SolanaChain.ID) else emptyList(),
            balances = listOf(amountOf(SolanaChain.SOL, lamports)) + tokens,
            transactionCount = null,
        )
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("Solana") {
        val address = address(target)
        val balances = listOf(amountOf(SolanaChain.SOL, balance(address))) + tokenBalances(address)
        val tokenAssets = balances.associate { (it.asset.contract ?: "") to it.asset }

        // Signatures newest first; stop at the first known one.
        val maxSignatures = if (knownTransactions.isEmpty()) FIRST_SYNC_SIGNATURES else MAX_SIGNATURES
        val newSignatures = mutableListOf<JsonElement>()
        var before: String? = null
        var truncated = false
        paging@ while (true) {
            val page = signatures(address, before, SIGNATURE_PAGE)
            for (entry in page) {
                val signature = entry.str("signature") ?: continue
                if (signature in knownTransactions) break@paging
                if (newSignatures.size >= maxSignatures) {
                    truncated = true
                    break@paging
                }
                newSignatures += entry
            }
            if (page.size < SIGNATURE_PAGE) break
            before = page.last().str("signature")
        }

        val transactions = newSignatures.flatMap { entry ->
            val signature = entry.str("signature")!!
            val tx = transaction(signature) ?: return@flatMap emptyList()
            val confirmed = entry.str("confirmationStatus") != "processed"
            parseTransaction(signature, tx, address, tokenAssets, confirmed)
        }
        SyncResult(
            balances = balances,
            transactions = transactions,
            activeChains = if (transactions.isNotEmpty() || balances.any { !it.amount.isZero }) listOf(SolanaChain.ID) else emptyList(),
            historyTruncated = truncated,
        )
    }

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("Solana supports addresses only")

    private suspend fun balance(address: String): BigInteger =
        parseBigInteger(rpc.call("getBalance", params(address, commitment())).at("value").content)

    private suspend fun signatures(address: String, before: String?, limit: Int): List<JsonElement> {
        val options = buildJsonObject {
            put("limit", limit)
            before?.let { put("before", it) }
        }
        return rpc.call("getSignaturesForAddress", params(address, options)).asList
    }

    private suspend fun transaction(signature: String): JsonElement? {
        val options = buildJsonObject {
            put("encoding", "jsonParsed")
            put("maxSupportedTransactionVersion", 0)
            put("commitment", "confirmed")
        }
        return rpc.call("getTransaction", params(signature, options)).takeIf { it is JsonObject }
    }

    /** SPL Token + Token-2022 balances, aggregated per mint, zero balances skipped. */
    private suspend fun tokenBalances(address: String): List<AssetAmount> {
        val byMint = LinkedHashMap<String, Pair<BigInteger, Int>>()
        for (program in listOf(SolanaChain.TOKEN_PROGRAM, SolanaChain.TOKEN_2022_PROGRAM)) {
            val result = rpc.call(
                "getTokenAccountsByOwner",
                params(address, buildJsonObject { put("programId", program) }, buildJsonObject { put("encoding", "jsonParsed") }),
            )
            for (account in result.arr("value")) {
                val info = account.at("account", "data", "parsed", "info") ?: continue
                val mint = info.str("mint") ?: continue
                val amount = info.at("tokenAmount", "amount").content ?: continue
                val decimals = info.at("tokenAmount", "decimals").content?.toIntOrNull() ?: continue
                val previous = byMint[mint]?.first ?: BigInteger.ZERO
                byMint[mint] = (previous + parseBigInteger(amount)) to decimals
            }
        }
        return byMint.filterValues { !it.first.isZero() }.map { (mint, value) -> amountOf(splAsset(mint, value.second), value.first) }
    }

    private fun params(vararg values: Any): JsonElement = buildJsonArray {
        values.forEach {
            when (it) {
                is String -> add(JsonPrimitive(it))
                is JsonElement -> add(it)
                else -> error("Unsupported param")
            }
        }
    }

    private fun commitment() = buildJsonObject { put("commitment", "confirmed") }

    private companion object {
        const val SIGNATURE_PAGE = 100
        const val FIRST_SYNC_SIGNATURES = 100
        const val MAX_SIGNATURES = 1_000
    }
}

internal fun splAsset(mint: String, decimals: Int): Asset {
    val known = SolanaTokens.KNOWN[mint]
    return tokenAsset(
        chainId = SolanaChain.ID,
        contract = mint,
        symbol = known?.symbol,
        name = known?.name,
        decimals = decimals,
        priceId = known?.coingeckoId ?: tokenPriceId(SolanaChain.COINGECKO_PLATFORM, mint),
    )
}

/**
 * Turns one `getTransaction` (jsonParsed) result into per-asset deltas for [owner]: SOL from
 * pre/post lamport balances of the owner's account key, SPL tokens from pre/post token balances
 * whose `owner` is [owner].
 */
internal fun parseTransaction(
    signature: String,
    tx: JsonElement,
    owner: String,
    knownAssets: Map<String, Asset>,
    confirmed: Boolean,
): List<ChainTransaction> {
    val meta = tx.obj("meta") ?: return emptyList()
    val time = tx.long("blockTime")?.let { Instant.fromEpochSeconds(it) } ?: Clock.System.now()
    val keys = tx.at("transaction", "message").arr("accountKeys").map { it.str("pubkey") ?: it.content }
    val result = mutableListOf<ChainTransaction>()

    val index = keys.indexOf(owner)
    if (index >= 0) {
        val pre = meta.arr("preBalances").getOrNull(index).content
        val post = meta.arr("postBalances").getOrNull(index).content
        val delta = parseBigInteger(post) - parseBigInteger(pre)
        val feePayer = index == 0
        if (!delta.isZero() || feePayer) {
            val fee = if (feePayer) parseBigInteger(meta.str("fee")) else null
            result += accountTransaction(SolanaChain.ID, signature, time, SolanaChain.SOL, delta, fee, confirmed)
        }
    }

    val tokenDeltas = LinkedHashMap<String, Pair<BigInteger, Int>>()
    fun collect(list: List<JsonElement>, sign: Int) {
        for (entry in list) {
            if (entry.str("owner") != owner) continue
            val mint = entry.str("mint") ?: continue
            val amount = parseBigInteger(entry.at("uiTokenAmount", "amount").content)
            val decimals = entry.at("uiTokenAmount", "decimals").content?.toIntOrNull() ?: continue
            val previous = tokenDeltas[mint]?.first ?: BigInteger.ZERO
            tokenDeltas[mint] = (if (sign > 0) previous + amount else previous - amount) to decimals
        }
    }
    collect(meta.arr("postTokenBalances"), +1)
    collect(meta.arr("preTokenBalances"), -1)
    tokenDeltas.filterValues { !it.first.isZero() }.forEach { (mint, value) ->
        val asset = knownAssets[mint] ?: splAsset(mint, value.second)
        result += accountTransaction(SolanaChain.ID, signature, time, asset, value.first, null, confirmed)
    }
    return result
}
