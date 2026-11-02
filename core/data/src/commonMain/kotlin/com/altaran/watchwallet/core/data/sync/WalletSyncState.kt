package com.altaran.watchwallet.core.data.sync

import com.altaran.watchwallet.core.datastore.SettingsDataSource
import com.altaran.watchwallet.core.network.NetworkJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Bookkeeping of a wallet between syncs, on the device only: when all chains were last searched for
 * activity, and the adapter's state per chain. Losing it is harmless (the next sync does more work).
 */
@Serializable
data class WalletSyncState(
    val discoveredAt: Long? = null,
    val chains: Map<String, String> = emptyMap(),
)

/** Where [WalletSyncState] lives: no database schema change, and it is not part of user data. */
interface WalletSyncStateStore {
    suspend fun load(walletId: Long): WalletSyncState

    suspend fun save(walletId: Long, state: WalletSyncState)

    suspend fun retain(walletIds: Set<Long>)
}

internal class DataStoreWalletSyncStateStore(private val settings: SettingsDataSource) : WalletSyncStateStore {
    override suspend fun load(walletId: Long): WalletSyncState = settings.walletSyncState(walletId)?.let { text ->
        try {
            NetworkJson.decodeFromString(WalletSyncState.serializer(), text)
        } catch (
            @Suppress("SwallowedException") e: SerializationException,
        ) {
            null
        }
    } ?: WalletSyncState()

    override suspend fun save(walletId: Long, state: WalletSyncState) =
        settings.setWalletSyncState(walletId, NetworkJson.encodeToString(WalletSyncState.serializer(), state))

    override suspend fun retain(walletIds: Set<Long>) = settings.retainWalletSyncStates(walletIds)
}
