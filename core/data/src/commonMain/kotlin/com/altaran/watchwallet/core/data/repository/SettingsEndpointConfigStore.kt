package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.datastore.SettingsDataSource
import com.altaran.watchwallet.core.endpoints.EndpointConfigStore
import com.altaran.watchwallet.core.endpoints.EndpointHostsStore
import kotlinx.coroutines.flow.first

/** Keeps the verified server list next to the settings; no database schema is involved. */
internal class SettingsEndpointConfigStore(private val settings: SettingsDataSource) :
    EndpointConfigStore,
    EndpointHostsStore {
    override suspend fun autoUpdateEnabled(): Boolean = settings.settings.first().network.autoUpdateEndpoints

    override suspend fun storedEnvelope(): String? = settings.endpointConfig.first()

    override suspend fun saveEnvelope(envelope: String) = settings.setEndpointConfig(envelope)

    override suspend fun lastCheckSeconds(): Long? = settings.endpointConfigLastCheck.first()

    override suspend fun saveLastCheckSeconds(seconds: Long) = settings.setEndpointConfigLastCheck(seconds)

    override suspend fun seenHosts(): Set<String>? = settings.endpointHostsSeen.first()

    override suspend fun saveSeenHosts(hosts: Set<String>) = settings.setEndpointHostsSeen(hosts)
}
