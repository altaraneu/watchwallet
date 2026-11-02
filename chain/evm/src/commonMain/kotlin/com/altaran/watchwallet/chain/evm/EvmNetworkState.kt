package com.altaran.watchwallet.chain.evm

import com.altaran.watchwallet.core.network.NetworkJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/** The three Blockscout history lists of an address; each one is paged on its own. */
internal enum class HistoryList { NATIVE, INTERNAL, TOKENS }

/** Where to go on in a list that is not read to its end; without [next] from its top. */
@Serializable
internal data class HistoryCursor(val next: JsonObject? = null)

/**
 * What a sync remembers about one network of a wallet, on the device only. The state is written
 * after the rows it describes: if the app dies in between, a page is fetched twice, which is
 * harmless because stored confirmed rows are kept.
 */
@Serializable
internal data class EvmNetworkState(
    /** Hash of balances and counters at the last full look at the history; equal = nothing new. */
    @SerialName("f") val fingerprint: String? = null,
    /** Pending transactions were stored and must be looked at again. */
    @SerialName("p") val pending: Boolean = false,
    /** Lists with older rows still to fetch, by [HistoryList] name. */
    @SerialName("c") val cursors: Map<String, HistoryCursor> = emptyMap(),
) {
    fun encode(): String = NetworkJson.encodeToString(serializer(), this)

    companion object {
        /** `null` for anything unreadable: the sync then starts over, which is safe. */
        fun decode(text: String): EvmNetworkState? = try {
            NetworkJson.decodeFromString(serializer(), text)
        } catch (
            @Suppress("SwallowedException") e: SerializationException,
        ) {
            null
        }
    }
}
