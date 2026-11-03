package com.altaran.watchwallet.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.altaran.watchwallet.core.model.QuoteCurrency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EndpointUpdatesChoiceTest {
    private class FakeStore(initial: Preferences) : DataStore<Preferences> {
        val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private val legacyOn = booleanPreferencesKey("endpoints_auto_update")
    private val legacyFile = stringPreferencesKey("endpoints_config")
    private val legacySeen = stringSetPreferencesKey("endpoints_hosts_seen")

    private fun source(prefs: Preferences = preferencesOf()) = SettingsDataSource(FakeStore(prefs)) { QuoteCurrency.DEFAULT }

    @Test
    fun aNewInstallIsOffWithNoChoiceRecorded() = runBlocking {
        val source = source()
        assertFalse(source.settings.first().network.autoUpdateEndpoints)
        assertNull(source.endpointUpdatesChoice.first())
    }

    /** Version 1.0 had neither the feature nor the key. */
    @Test
    fun anUpdatingUserIsOff() = runBlocking {
        val source = source(preferencesOf(booleanPreferencesKey("discreet_mode") to true, stringPreferencesKey("proxy_host") to "127.0.0.1"))
        assertFalse(source.settings.first().network.autoUpdateEndpoints)
        assertNull(source.endpointUpdatesChoice.first())
    }

    /**
     * Test builds defaulted to on and wrote "on" whenever any network setting was saved, so the old
     * value proves nothing. Nor was the file they downloaded ever approved.
     */
    @Test
    fun anOldStoredOnIsNoChoiceAndItsUnapprovedFileIsNotUsed() = runBlocking {
        val store = FakeStore(preferencesOf(legacyOn to true, legacyFile to "envelope", legacySeen to setOf("mempool.space")))
        val source = SettingsDataSource(store) { QuoteCurrency.DEFAULT }
        assertFalse(source.settings.first().network.autoUpdateEndpoints)
        assertNull(source.endpointConfig.first())

        source.initializeDefaults()
        assertFalse(legacyOn in store.state.value)
        assertFalse(legacyFile in store.state.value)
        assertFalse(legacySeen in store.state.value)
    }

    /** The old bug: saving a proxy or a server stored a choice nobody made. */
    @Test
    fun savingAnotherNetworkSettingRecordsNoChoice() = runBlocking {
        val source = source()
        val network = source.settings.first().network
        source.setNetwork(network.copy(proxyEnabled = true, proxyHost = "10.0.0.1"))
        assertNull(source.endpointUpdatesChoice.first())
        assertTrue(source.settings.first().network.proxyEnabled)
    }

    @Test
    fun theSwitchStoresAndKeepsTheChoice() = runBlocking {
        val source = source()
        val network = source.settings.first().network
        source.setNetwork(network.copy(autoUpdateEndpoints = true))
        assertEquals(true, source.endpointUpdatesChoice.first())
        assertTrue(source.settings.first().network.autoUpdateEndpoints)

        source.setNetwork(source.settings.first().network.copy(proxyEnabled = true))
        assertEquals(true, source.endpointUpdatesChoice.first(), "another setting does not touch it")

        source.setNetwork(source.settings.first().network.copy(autoUpdateEndpoints = false))
        assertEquals(false, source.endpointUpdatesChoice.first(), "off is a choice too")
        assertFalse(source.settings.first().network.autoUpdateEndpoints)
    }

    @Test
    fun applyingAndDecliningClearWhatWaitsInOneWrite() = runBlocking {
        val source = source()
        source.setEndpointConfigPending("a")
        assertEquals("a", source.endpointConfigPending.first())
        assertNull(source.endpointConfig.first(), "waiting has no effect on what is in force")

        source.applyEndpointConfig("a")
        assertEquals("a", source.endpointConfig.first())
        assertNull(source.endpointConfigPending.first())

        source.setEndpointConfigPending("b")
        source.declineEndpointConfig("b")
        assertEquals("a", source.endpointConfig.first())
        assertEquals("b", source.endpointConfigDeclined.first())
        assertNull(source.endpointConfigPending.first())
    }
}
