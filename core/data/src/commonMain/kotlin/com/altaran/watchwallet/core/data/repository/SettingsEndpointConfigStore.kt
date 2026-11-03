package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.datastore.SettingsDataSource
import com.altaran.watchwallet.core.endpoints.EndpointConfigStore
import kotlinx.coroutines.flow.first

/** Keeps the verified server list next to the settings; no database schema is involved. */
internal class SettingsEndpointConfigStore(private val settings: SettingsDataSource) : EndpointConfigStore {
    override suspend fun autoUpdateEnabled(): Boolean = settings.settings.first().network.autoUpdateEndpoints

    override suspend fun storedEnvelope(): String? = settings.endpointConfig.first()

    override suspend fun pendingEnvelope(): String? = settings.endpointConfigPending.first()

    override suspend fun declinedEnvelope(): String? = settings.endpointConfigDeclined.first()

    override suspend fun savePendingEnvelope(envelope: String) = settings.setEndpointConfigPending(envelope)

    override suspend fun applyEnvelope(envelope: String) = settings.applyEndpointConfig(envelope)

    override suspend fun declineEnvelope(envelope: String) = settings.declineEndpointConfig(envelope)

    override suspend fun lastCheckSeconds(): Long? = settings.endpointConfigLastCheck.first()

    override suspend fun saveLastCheckSeconds(seconds: Long) = settings.setEndpointConfigLastCheck(seconds)
}
