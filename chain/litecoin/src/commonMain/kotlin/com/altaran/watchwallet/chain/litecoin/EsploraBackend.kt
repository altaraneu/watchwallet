package com.altaran.watchwallet.chain.litecoin

import com.altaran.watchwallet.chain.common.UtxoAddressInfo
import com.altaran.watchwallet.chain.common.UtxoBackend
import com.altaran.watchwallet.chain.common.UtxoHistory
import com.altaran.watchwallet.chain.common.UtxoIo
import com.altaran.watchwallet.chain.common.UtxoTx
import com.altaran.watchwallet.core.network.Throttle
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class EsploraAddress(
    @SerialName("chain_stats") val chainStats: EsploraStats = EsploraStats(),
    @SerialName("mempool_stats") val mempoolStats: EsploraStats = EsploraStats(),
)

@Serializable
internal data class EsploraStats(
    @SerialName("funded_txo_sum") val funded: Long = 0,
    @SerialName("spent_txo_sum") val spent: Long = 0,
    @SerialName("tx_count") val txCount: Int = 0,
)

@Serializable
internal data class EsploraTx(
    val txid: String,
    val status: EsploraStatus,
    val fee: Long = 0,
    val vin: List<EsploraVin> = emptyList(),
    val vout: List<EsploraVout> = emptyList(),
)

@Serializable
internal data class EsploraStatus(
    val confirmed: Boolean,
    @SerialName("block_time") val blockTime: Long? = null,
)

@Serializable
internal data class EsploraVin(val prevout: EsploraVout? = null)

@Serializable
internal data class EsploraVout(
    @SerialName("scriptpubkey_address") val address: String? = null,
    val value: Long = 0,
)

/** Esplora REST backend (same API as mempool.space). Only single addresses are sent. */
internal class EsploraBackend(
    private val http: HttpClient,
    baseUrl: String,
    private val throttle: Throttle,
) : UtxoBackend {
    private val base = baseUrl.trimEnd('/')

    override suspend fun address(address: String): UtxoAddressInfo {
        val info: EsploraAddress = throttle.run { http.get("$base/address/$address").body() }
        val c = info.chainStats
        val m = info.mempoolStats
        return UtxoAddressInfo(c.txCount + m.txCount, c.funded - c.spent + m.funded - m.spent)
    }

    override suspend fun history(address: String, known: Set<String>, maxPages: Int): UtxoHistory {
        val collected = mutableListOf<UtxoTx>()
        var lastSeen: String? = null
        repeat(maxPages) {
            val path = if (lastSeen == null) "txs" else "txs/chain/$lastSeen"
            val page: List<EsploraTx> = throttle.run { http.get("$base/address/$address/$path").body() }
            collected += page.map { it.toUtxoTx() }
            val confirmed = page.filter { it.status.confirmed }
            if (confirmed.size < PAGE_SIZE || confirmed.any { it.txid in known }) return UtxoHistory(collected, truncated = false)
            lastSeen = confirmed.last().txid
        }
        return UtxoHistory(collected, truncated = true)
    }

    private fun EsploraTx.toUtxoTx() = UtxoTx(
        txid = txid,
        blockTime = status.blockTime,
        confirmed = status.confirmed,
        fee = fee,
        inputs = vin.map { UtxoIo(it.prevout?.address, it.prevout?.value ?: 0L) },
        outputs = vout.map { UtxoIo(it.address, it.value) },
    )

    companion object {
        const val PAGE_SIZE = 25
    }
}
