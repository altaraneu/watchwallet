package com.altaran.watchwallet.chain.bitcoin

import com.altaran.watchwallet.core.network.Throttle
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class EsploraAddress(
    val address: String,
    @SerialName("chain_stats") val chainStats: EsploraStats,
    @SerialName("mempool_stats") val mempoolStats: EsploraStats,
) {
    val txCount: Int get() = chainStats.txCount + mempoolStats.txCount

    /** Confirmed + unconfirmed balance in satoshi. */
    val balance: Long
        get() = chainStats.funded - chainStats.spent + mempoolStats.funded - mempoolStats.spent
}

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
internal data class EsploraVin(
    val prevout: EsploraVout? = null,
    @SerialName("is_coinbase") val isCoinbase: Boolean = false,
)

@Serializable
internal data class EsploraVout(
    @SerialName("scriptpubkey_address") val address: String? = null,
    val value: Long = 0,
)

/**
 * Minimal Esplora REST client (mempool.space, blockstream.info, self-hosted electrs/esplora).
 * Only individual addresses are sent; never an extended public key.
 *
 * With several [baseUrls] a failing instance (timeout, rate limit, outage) is skipped and the
 * last working one is preferred for later calls. Paging uses txids, so it works across them.
 */
internal class EsploraClient(
    private val http: HttpClient,
    private val baseUrls: List<String>,
    private val throttle: Throttle,
) {
    init {
        require(baseUrls.isNotEmpty())
    }

    private var preferred = 0

    private suspend inline fun <reified T> get(path: String): T {
        var lastError: Exception? = null
        for (offset in baseUrls.indices) {
            val index = (preferred + offset) % baseUrls.size
            try {
                val result: T = throttle.run { http.get("${baseUrls[index].trimEnd('/')}/$path").body() }
                preferred = index
                return result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No Esplora instance")
    }

    suspend fun address(address: String): EsploraAddress = get("address/$address")

    /**
     * First call (`lastSeenTxid == null`) returns mempool transactions plus the newest
     * [PAGE_SIZE] confirmed ones; subsequent calls return the next confirmed page.
     */
    suspend fun transactions(address: String, lastSeenTxid: String?): List<EsploraTx> =
        get(if (lastSeenTxid == null) "address/$address/txs" else "address/$address/txs/chain/$lastSeenTxid")

    companion object {
        const val PAGE_SIZE = 25
    }
}
