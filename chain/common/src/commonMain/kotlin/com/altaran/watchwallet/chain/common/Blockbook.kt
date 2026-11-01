package com.altaran.watchwallet.chain.common

import com.altaran.watchwallet.core.network.Throttle
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

@Serializable
internal data class BlockbookAddress(
    val page: Int = 1,
    val totalPages: Int = 1,
    val balance: String = "0",
    val unconfirmedBalance: String = "0",
    val txs: Int = 0,
    val unconfirmedTxs: Int = 0,
    val transactions: List<BlockbookTx> = emptyList(),
)

@Serializable
internal data class BlockbookTx(
    val txid: String,
    val vin: List<BlockbookIo> = emptyList(),
    val vout: List<BlockbookIo> = emptyList(),
    val blockHeight: Long = -1,
    val confirmations: Long = 0,
    val blockTime: Long? = null,
    val fees: String = "0",
)

@Serializable
internal data class BlockbookIo(
    val addresses: List<String> = emptyList(),
    val value: String = "0",
)

/**
 * Blockbook REST v2 backend (Trezor's public instances). Only the per-address endpoint is used;
 * the xpub endpoint is never called, so extended public keys never leave the device.
 *
 * @param normalize canonical address form, applied to addresses reported by Blockbook.
 */
class BlockbookBackend(
    private val http: HttpClient,
    baseUrl: String,
    private val throttle: Throttle,
    private val normalize: (String) -> String = { it },
) : UtxoBackend {
    private val base = baseUrl.trimEnd('/')

    override suspend fun address(address: String): UtxoAddressInfo {
        val info: BlockbookAddress = throttle.run {
            http.get("$base/api/v2/address/$address") { parameter("details", "basic") }.body()
        }
        val balance = (info.balance.toLongOrNull() ?: 0L) + (info.unconfirmedBalance.toLongOrNull() ?: 0L)
        return UtxoAddressInfo(txCount = info.txs + info.unconfirmedTxs, balance = balance)
    }

    override suspend fun history(address: String, known: Set<String>, maxPages: Int): UtxoHistory {
        val collected = mutableListOf<UtxoTx>()
        for (page in 1..maxPages) {
            val info: BlockbookAddress = throttle.run {
                http.get("$base/api/v2/address/$address") {
                    parameter("details", "txs")
                    parameter("page", page)
                    parameter("pageSize", PAGE_SIZE)
                }.body()
            }
            val txs = info.transactions.map { it.toUtxoTx() }
            collected += txs
            val reachedKnown = txs.any { it.confirmed && it.txid in known }
            if (reachedKnown || page >= info.totalPages || txs.isEmpty()) return UtxoHistory(collected, truncated = false)
        }
        return UtxoHistory(collected, truncated = true)
    }

    private fun BlockbookTx.toUtxoTx() = UtxoTx(
        txid = txid,
        blockTime = blockTime?.takeIf { it > 0 },
        confirmed = confirmations > 0 && blockHeight > 0,
        fee = fees.toLongOrNull() ?: 0L,
        inputs = vin.map { UtxoIo(it.addresses.singleOrNull()?.let(normalize), it.value.toLongOrNull() ?: 0L) },
        outputs = vout.map { UtxoIo(it.addresses.singleOrNull()?.let(normalize), it.value.toLongOrNull() ?: 0L) },
    )

    companion object {
        const val PAGE_SIZE = 50
    }
}
