package com.altaran.watchwallet.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import com.altaran.watchwallet.core.model.QuoteCurrency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteIconsDefaultTest {
    private class FakeStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private val key = booleanPreferencesKey("remote_icons")

    private fun remoteIcons(prefs: Preferences) = runBlocking {
        SettingsDataSource(FakeStore(prefs)) { QuoteCurrency.DEFAULT }.settings.first().remoteIcons
    }

    @Test
    fun aNewUserDownloadsNothing() = assertFalse(remoteIcons(preferencesOf()))

    /** Version 1.0 stored other settings but no value here unless the switch was flipped. */
    @Test
    fun anUpdatingUserWhoNeverChoseStopsDownloading() =
        assertFalse(remoteIcons(preferencesOf(booleanPreferencesKey("discreet_mode") to true)))

    @Test
    fun anExplicitChoiceIsKept() {
        assertTrue(remoteIcons(mutablePreferencesOf(key to true)))
        assertFalse(remoteIcons(mutablePreferencesOf(key to false)))
    }

    @Test
    fun theSwitchStoresTheChoice() = runBlocking {
        val source = SettingsDataSource(FakeStore(preferencesOf())) { QuoteCurrency.DEFAULT }
        source.setRemoteIcons(true)
        assertTrue(source.settings.first().remoteIcons)
    }
}
