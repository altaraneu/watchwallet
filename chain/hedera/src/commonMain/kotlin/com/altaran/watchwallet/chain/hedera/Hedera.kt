package com.altaran.watchwallet.chain.hedera

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.chain.common.accountTransaction
import com.altaran.watchwallet.chain.common.amountOf
import com.altaran.watchwallet.chain.common.arr
import com.altaran.watchwallet.chain.common.at
import com.altaran.watchwallet.chain.common.chainCall
import com.altaran.watchwallet.chain.common.nativeAsset
import com.altaran.watchwallet.chain.common.notFoundAsNull
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
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object HederaChain {
    val ID = ChainId("hedera")

    val CHAIN = Chain(
        id = ID,
        family = ChainFamily.HEDERA,
        name = "Hedera",
        nativeSymbol = "HBAR",
        nativeDecimals = 8,
        nativePriceId = "hedera-hashgraph",
        explorerTxUrl = "https://hashscan.io/mainnet/transaction/{tx}",
        explorerAddressUrl = "https://hashscan.io/mainnet/account/{address}",
        color = 0xFF222222,
    )

    val HBAR: Asset = nativeAsset(CHAIN)

    const val DEFAULT_MIRROR_URL = "https://mainnet-public.mirrornode.hedera.com/api/v1"
    const val COINGECKO_PLATFORM = "hedera-hashgraph"
}

/**
 * Hedera account ids `shard.realm.num` (usually `0.0.N`) with an optional HIP-15 checksum
 * (`0.0.123-vfmkw`), which is verified against the mainnet ledger id (`0x00`) and stripped.
 */
object HederaAddresses {
    private val SHAPE = Regex("""^(\d{1,10})\.(\d{1,10})\.(\d{1,19})(?:-([a-z]{5}))?$""")

    fun parse(input: String): WatchTarget.Address? {
        val match = SHAPE.matchEntire(input.trim()) ?: return null
        val (shard, realm, num, checksum) = match.destructured
        val number = num.toULongOrNull() ?: return null
        val id = "${shard.toLong()}.${realm.toLong()}.$number"
        if (checksum.isNotEmpty() && checksum != checksum(id)) return null
        return WatchTarget.Address(ChainFamily.HEDERA, HederaChain.ID, id)
    }

    /** HIP-15 checksum of an account id for the mainnet ledger id (`0x00`). */
    fun checksum(id: String, ledgerId: ByteArray = byteArrayOf(0)): String {
        val p3 = 26L * 26 * 26
        val p5 = 26L * 26 * 26 * 26 * 26
        val w = 31L
        val m = 1_000_003L
        val digits = id.map { if (it == '.') 10 else it - '0' }
        var sd0 = 0L
        var sd1 = 0L
        var sd = 0L
        digits.forEachIndexed { i, d ->
            if (i % 2 == 0) sd0 = (sd0 + d) % 11 else sd1 = (sd1 + d) % 11
            sd = (w * sd + d) % p3
        }
        var sh = 0L
        for (b in ledgerId + ByteArray(6)) sh = (w * sh + (b.toInt() and 0xFF)) % p5
        val c = ((((id.length % 5) * 11L + sd0) * 11 + sd1) * p3 + sd + sh) % p5
        var cp = (c * m) % p5
        val letters = CharArray(5)
        for (i in 4 downTo 0) {
            letters[i] = 'a' + (cp % 26).toInt()
            cp /= 26
        }
        return letters.concatToString()
    }
}

/**
 * Hedera via the public mirror node: HBAR and HTS fungible token balances and the account's
 * transactions (net HBAR transfers include the fee for the payer).
 */
class HederaAdapter(
    private val http: HttpClient,
    mirrorUrl: String = HederaChain.DEFAULT_MIRROR_URL,
) : ChainAdapter {
    private val base = mirrorUrl.trimEnd('/')
    private val origin = base.substringBefore("/api/v1")
    private val throttle = Throttle(maxConcurrent = 3, minInterval = 100.milliseconds, maxJitter = 80.milliseconds)
    private val tokenCache = HashMap<String, Asset?>()

    override val family = ChainFamily.HEDERA
    override val chains = listOf(HederaChain.CHAIN)

    override suspend fun probe(target: WatchTarget): ProbeResult = chainCall("Hedera") {
        val account = account(address(target))
            ?: return@chainCall ProbeResult(false, emptyList(), listOf(amountOf(HederaChain.HBAR, BigInteger.ZERO)), 0)
        ProbeResult(true, listOf(HederaChain.ID), balances(account), transactionCount = null)
    }

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>): SyncResult = chainCall("Hedera") {
        val id = address(target)
        val account = account(id)
            ?: return@chainCall SyncResult(listOf(amountOf(HederaChain.HBAR, BigInteger.ZERO)), emptyList())
        val balances = balances(account)
        val maxPages = if (knownTransactions.isEmpty()) FIRST_SYNC_PAGES else MAX_PAGES

        val transactions = mutableListOf<ChainTransaction>()
        var url: String? = "$base/transactions?account.id=$id&order=desc&limit=$PAGE_SIZE"
        var pages = 0
        var truncated = false
        paging@ while (url != null) {
            val page = get(url)
            for (tx in page.arr("transactions")) {
                val hash = transactionHash(tx) ?: continue
                if (hash in knownTransactions) break@paging
                transactions += parseHederaTransaction(tx, id) { tokenId -> token(tokenId) }
            }
            url = page.at("links").str("next")?.let { origin + it }
            if (url != null && ++pages >= maxPages) {
                truncated = true
                break
            }
        }
        SyncResult(balances, transactions, activeChains = listOf(HederaChain.ID), historyTruncated = truncated)
    }

    private fun address(target: WatchTarget): String =
        (target as? WatchTarget.Address)?.address ?: throw ChainDataException("Hedera supports account ids only")

    private suspend fun account(id: String): JsonElement? = notFoundAsNull {
        get("$base/accounts/$id?transactions=false")
    }

    private suspend fun balances(account: JsonElement): List<AssetAmount> {
        val result = mutableListOf(amountOf(HederaChain.HBAR, account.at("balance").str("balance")))
        // Associated tokens without a balance must not use up the limit before real holdings.
        val held = account.at("balance").arr("tokens").filterNot { parseBigInteger(it.str("balance")).isZero() }
        for (entry in held.take(MAX_TOKENS)) {
            val tokenId = entry.str("token_id") ?: continue
            val raw = parseBigInteger(entry.str("balance"))
            result += amountOf(token(tokenId) ?: continue, raw)
        }
        return result
    }

    /** Fungible HTS token metadata (cached); `null` for NFTs or unknown tokens. */
    private suspend fun token(tokenId: String): Asset? = tokenCache.getOrPut(tokenId) {
        val info = notFoundAsNull { get("$base/tokens/$tokenId") }
        if (info == null || info.str("type") == "NON_FUNGIBLE_UNIQUE") {
            null
        } else {
            val decimals = info.str("decimals")?.toIntOrNull()
            decimals?.let {
                tokenAsset(
                    HederaChain.ID,
                    tokenId,
                    info.str("symbol"),
                    info.str("name"),
                    it,
                    tokenPriceId(HederaChain.COINGECKO_PLATFORM, tokenId),
                )
            }
        }
    }

    private suspend fun get(url: String): JsonElement = throttle.run { http.get(url).body<JsonElement>() }

    private companion object {
        const val PAGE_SIZE = 100
        const val FIRST_SYNC_PAGES = 2
        const val MAX_PAGES = 20
        const val MAX_TOKENS = 50
    }
}

/** Unique id of a mirror-node transaction: transaction id, plus the nonce of child transactions. */
internal fun transactionHash(tx: JsonElement): String? {
    val id = tx.str("transaction_id") ?: return null
    val nonce = tx.str("nonce")?.toIntOrNull() ?: 0
    return if (nonce > 0) "$id/$nonce" else id
}

/** Net HBAR and token changes of [account] in one mirror-node transaction. */
internal suspend fun parseHederaTransaction(
    tx: JsonElement,
    account: String,
    tokenOf: suspend (String) -> Asset?,
): List<ChainTransaction> {
    val hash = transactionHash(tx) ?: return emptyList()
    val time =
        tx.str("consensus_timestamp")?.substringBefore('.')?.toLongOrNull()?.let { Instant.fromEpochSeconds(it) }
            ?: Clock.System.now()
    val payer = tx.str("transaction_id")?.substringBefore('-')
    val result = mutableListOf<ChainTransaction>()

    var hbar = BigInteger.ZERO
    var touched = false
    for (transfer in tx.arr("transfers")) {
        if (transfer.str("account") != account) continue
        hbar += parseBigInteger(transfer.str("amount"))
        touched = true
    }
    if (touched || payer == account) {
        val fee = if (payer == account) parseBigInteger(tx.str("charged_tx_fee")) else null
        result += accountTransaction(HederaChain.ID, hash, time, HederaChain.HBAR, hbar, fee)
    }

    val tokens = LinkedHashMap<String, BigInteger>()
    for (transfer in tx.arr("token_transfers")) {
        if (transfer.str("account") != account) continue
        val tokenId = transfer.str("token_id") ?: continue
        tokens[tokenId] = (tokens[tokenId] ?: BigInteger.ZERO) + parseBigInteger(transfer.str("amount"))
    }
    for ((tokenId, delta) in tokens) {
        if (delta.isZero()) continue
        val asset = tokenOf(tokenId) ?: continue
        result += accountTransaction(HederaChain.ID, hash, time, asset, delta, null)
    }
    return result
}
