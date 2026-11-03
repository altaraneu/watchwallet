package com.altaran.watchwallet.core.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.altaran.watchwallet.core.data.repository.DefaultSettingsRepository
import com.altaran.watchwallet.core.datastore.SettingsDataSource
import com.altaran.watchwallet.core.model.NetworkSettings
import com.altaran.watchwallet.core.model.QuoteCurrency
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ServerFailureCountsTest {
    private val spacing = ServerFailureCounts.MIN_SPACING_MILLIS
    private val solana = "solana"

    private fun rounds(vararg failedAt: Pair<Set<String>, Long>, connected: Boolean = true): Map<String, ServerFailureCounts.Entry> =
        failedAt.fold(emptyMap()) { counts, (failed, at) -> ServerFailureCounts.next(counts, failed, connected, at) }

    private fun source() = SettingsDataSource(
        PreferenceDataStoreFactory.create(produceFile = { File(createTempDirectory().toFile(), "s.preferences_pb") }),
    ) { QuoteCurrency.DEFAULT }

    private fun log(source: SettingsDataSource, now: () -> Long, own: Set<String> = emptySet()) = SettingsServerFailureLog(source, { own }, now)

    @Test
    fun threeSpacedRoundsCount() {
        val counts = rounds(setOf(solana) to 0, setOf(solana) to spacing, setOf(solana) to 2 * spacing)
        assertEquals(3, counts.getValue(solana).count)
    }

    @Test
    fun refreshingDuringOneOutageCountsOnce() {
        val counts = rounds(setOf(solana) to 0, setOf(solana) to 1, setOf(solana) to 2, setOf(solana) to spacing - 1)
        assertEquals(1, counts.getValue(solana).count)
    }

    @Test
    fun aSuccessStartsOver() {
        val counts = rounds(setOf(solana) to 0, setOf(solana) to spacing, emptySet<String>() to 2 * spacing, setOf(solana) to 3 * spacing)
        assertEquals(1, counts.getValue(solana).count)
    }

    /** Without a connection nothing can be said about the servers. */
    @Test
    fun aDeviceOfflineCountsNothing() {
        assertEquals(emptyMap<String, ServerFailureCounts.Entry>(), rounds(setOf(solana) to 0, setOf(solana) to spacing, connected = false))
    }

    @Test
    fun theStoredFormRoundTripsAndIgnoresDamage() {
        val counts = mapOf("evm:1" to ServerFailureCounts.Entry(3, 42))
        assertEquals(counts, ServerFailureCounts.parse(ServerFailureCounts.format(counts)))
        assertEquals(emptyMap<String, ServerFailureCounts.Entry>(), ServerFailureCounts.parse(setOf("garbage", "a|b|c")))
    }

    private suspend fun fail(log: SettingsServerFailureLog, repeat: Int = ServerFailureCounts.REPEATED) = repeat(repeat) {
        log.round(setOf(solana), connected = true)
    }

    @Test
    fun theHintAppearsOnlyAfterRepeatedFailuresOfAUserWhoNeverDecided() = runBlocking {
        val source = source()
        val repository = DefaultSettingsRepository(source)
        var now = 0L
        val log = log(source, { now.also { now += spacing } })
        assertFalse("nothing failed yet", repository.suggestServerListUpdates.first())
        fail(log, ServerFailureCounts.REPEATED - 1)
        assertFalse("not repeated enough", repository.suggestServerListUpdates.first())
        fail(log, 1)
        assertTrue(repository.suggestServerListUpdates.first())
    }

    @Test
    fun notNowEndsTheHintForGood() = runBlocking {
        val source = source()
        val repository = DefaultSettingsRepository(source)
        var now = 0L
        fail(log(source, { now.also { now += spacing } }))
        repository.declineServerListUpdates()
        assertFalse(repository.suggestServerListUpdates.first())
        assertFalse("updates stay off", source.settings.first().network.autoUpdateEndpoints)
    }

    @Test
    fun turningUpdatesOnEndsTheHint() = runBlocking {
        val source = source()
        val repository = DefaultSettingsRepository(source)
        var now = 0L
        fail(log(source, { now.also { now += spacing } }))
        repository.setNetwork(NetworkSettings(autoUpdateEndpoints = true))
        assertFalse(repository.suggestServerListUpdates.first())
    }

    /** A list cannot help a server the user chose: it always wins over the list. */
    @Test
    fun theUsersOwnServerFailingIsNoReasonForTheHint() = runBlocking {
        val source = source()
        val repository = DefaultSettingsRepository(source)
        var now = 0L
        fail(log(source, { now.also { now += spacing } }, own = setOf(solana)))
        assertFalse(repository.suggestServerListUpdates.first())
        assertEquals(emptySet<String>(), source.serverFailures.first())
    }
}
