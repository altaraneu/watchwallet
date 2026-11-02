package com.altaran.watchwallet.core.endpoints

import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeStore(
    var enabled: Boolean = true,
    var envelope: String? = null,
    var lastCheck: Long? = null,
) : EndpointConfigStore {
    override suspend fun autoUpdateEnabled() = enabled

    override suspend fun storedEnvelope() = envelope

    override suspend fun saveEnvelope(envelope: String) {
        this.envelope = envelope
    }

    override suspend fun lastCheckSeconds() = lastCheck

    override suspend fun saveLastCheckSeconds(seconds: Long) {
        lastCheck = seconds
    }
}

class EndpointConfigUpdaterTest {
    private val requests = mutableListOf<String>()

    /** Answers from [responses] by URL; a URL without an answer fails like an unreachable host. */
    private fun updater(
        store: FakeStore,
        responses: Map<String, String>,
        verifier: EndpointFileVerifier = com.altaran.watchwallet.core.endpoints.verifier,
    ): EndpointConfigUpdater {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            requests += url
            responses[url]?.let { respond(it) } ?: respondError(HttpStatusCode.NotFound)
        }
        return EndpointConfigUpdater(createHttpClient(engine = engine), store, verifier, listOf(PRIMARY, FALLBACK), { NOW })
    }

    @Test
    fun storesValidFile() = runTest {
        val store = FakeStore()
        assertEquals(EndpointUpdateResult.UPDATED, updater(store, mapOf(PRIMARY to signed())).updateIfDue())
        assertEquals(signed(), store.envelope)
        assertEquals(NOW, store.lastCheck)
        assertEquals(listOf(PRIMARY), requests)
    }

    @Test
    fun disabledMakesNoRequest() = runTest {
        val store = FakeStore(enabled = false)
        assertEquals(EndpointUpdateResult.DISABLED, updater(store, mapOf(PRIMARY to signed())).updateIfDue())
        assertEquals(emptyList(), requests)
        assertNull(store.envelope)
    }

    @Test
    fun checksAtMostOnceADay() = runTest {
        val store = FakeStore(lastCheck = NOW - EndpointConfigUpdater.CHECK_INTERVAL_SECONDS + 1)
        assertEquals(EndpointUpdateResult.NOT_DUE, updater(store, mapOf(PRIMARY to signed())).updateIfDue())
        assertEquals(emptyList(), requests)

        store.lastCheck = NOW - EndpointConfigUpdater.CHECK_INTERVAL_SECONDS
        assertEquals(EndpointUpdateResult.UPDATED, updater(store, mapOf(PRIMARY to signed())).updateIfDue())
    }

    @Test
    fun failedAttemptAlsoCountsForTheDay() = runTest {
        val store = FakeStore()
        assertEquals(EndpointUpdateResult.FAILED, updater(store, emptyMap()).updateIfDue())
        assertEquals(NOW, store.lastCheck)
        requests.clear()
        assertEquals(EndpointUpdateResult.NOT_DUE, updater(store, emptyMap()).updateIfDue())
        assertEquals(emptyList(), requests)
    }

    @Test
    fun lastCheckInTheFutureDoesNotBlockUpdates() = runTest {
        val store = FakeStore(lastCheck = NOW + 1_000)
        assertEquals(EndpointUpdateResult.UPDATED, updater(store, mapOf(PRIMARY to signed())).updateIfDue())
    }

    @Test
    fun offlineKeepsWhatIsStored() = runTest {
        val stored = signed(version = 3)
        val store = FakeStore(envelope = stored)
        assertEquals(EndpointUpdateResult.FAILED, updater(store, emptyMap()).updateIfDue())
        assertEquals(stored, store.envelope)
        assertEquals(listOf(PRIMARY, FALLBACK), requests)
    }

    @Test
    fun fallsBackWhenPrimaryIsDown() = runTest {
        val store = FakeStore()
        assertEquals(EndpointUpdateResult.UPDATED, updater(store, mapOf(FALLBACK to signed())).updateIfDue())
        assertEquals(signed(), store.envelope)
    }

    @Test
    fun fallsBackWhenPrimaryServesABadFile() = runTest {
        val text = payload()
        val tampered = envelope(text.replace("new.test", "evil.test"), signature(text))
        val store = FakeStore()
        assertEquals(EndpointUpdateResult.UPDATED, updater(store, mapOf(PRIMARY to tampered, FALLBACK to signed())).updateIfDue())
        assertEquals(signed(), store.envelope)
    }

    @Test
    fun badlySignedFileIsNeverStored() = runTest {
        val text = payload()
        val store = FakeStore()
        val result = updater(store, mapOf(PRIMARY to envelope(text, signature(text, TestKeys.OTHER_SECRET)))).updateIfDue()
        assertEquals(EndpointUpdateResult.FAILED, result)
        assertNull(store.envelope)
    }

    @Test
    fun lowerVersionIsRejected() = runTest {
        val stored = signed(version = 5)
        val store = FakeStore(envelope = stored)
        assertEquals(EndpointUpdateResult.FAILED, updater(store, mapOf(PRIMARY to signed(version = 4))).updateIfDue())
        assertEquals(stored, store.envelope)
    }

    /** Otherwise the fallback to bundled at expiry would reopen the door to replays. */
    @Test
    fun lowerVersionIsRejectedEvenAfterTheStoredFileExpired() = runTest {
        val stored = signed(version = 5, expires = NOW - 1)
        val store = FakeStore(envelope = stored)
        assertEquals(EndpointUpdateResult.FAILED, updater(store, mapOf(PRIMARY to signed(version = 4))).updateIfDue())
        assertEquals(stored, store.envelope)
    }

    @Test
    fun sameVersionChangesNothing() = runTest {
        val stored = signed(version = 5)
        val store = FakeStore(envelope = stored)
        val other = signed(version = 5, entries = listOf(entry(urls = arrayOf("https://other.test"))))
        assertEquals(EndpointUpdateResult.UNCHANGED, updater(store, mapOf(PRIMARY to other)).updateIfDue())
        assertEquals(stored, store.envelope)
    }

    @Test
    fun higherVersionReplaces() = runTest {
        val store = FakeStore(envelope = signed(version = 5))
        assertEquals(EndpointUpdateResult.UPDATED, updater(store, mapOf(PRIMARY to signed(version = 6))).updateIfDue())
        assertEquals(signed(version = 6), store.envelope)
    }

    @Test
    fun expiredFileIsRejected() = runTest {
        val store = FakeStore()
        assertEquals(EndpointUpdateResult.FAILED, updater(store, mapOf(PRIMARY to signed(expires = NOW))).updateIfDue())
        assertNull(store.envelope)
    }

    @Test
    fun fileExpiringTooFarAheadIsRejected() = runTest {
        val store = FakeStore()
        val tooLong = signed(expires = NOW + EndpointFile.MAX_VALIDITY_SECONDS + 1)
        assertEquals(EndpointUpdateResult.FAILED, updater(store, mapOf(PRIMARY to tooLong)).updateIfDue())
        assertNull(store.envelope)
    }

    @Test
    fun fileAtTheValidityLimitIsAccepted() = runTest {
        val store = FakeStore()
        val longest = signed(expires = NOW + EndpointFile.MAX_VALIDITY_SECONDS)
        assertEquals(EndpointUpdateResult.UPDATED, updater(store, mapOf(PRIMARY to longest)).updateIfDue())
    }

    /** What the recovery key is for: a stolen key's file cannot block it with a huge version. */
    @Test
    fun recoveryFileBeatsAHigherVersionOfTheNormalKey() = runTest {
        val store = FakeStore(envelope = signed(version = Long.MAX_VALUE))
        val rescue = signedByRecovery(version = 2)
        assertEquals(EndpointUpdateResult.UPDATED, updater(store, mapOf(PRIMARY to rescue), recoverableVerifier).updateIfDue())
        assertEquals(rescue, store.envelope)
    }

    @Test
    fun recoveryFileOfTheSameVersionStillReplacesTheNormalOne() = runTest {
        val store = FakeStore(envelope = signed(version = 5))
        val rescue = signedByRecovery(version = 5)
        assertEquals(EndpointUpdateResult.UPDATED, updater(store, mapOf(PRIMARY to rescue), recoverableVerifier).updateIfDue())
        assertEquals(rescue, store.envelope)
    }

    @Test
    fun afterRecoveryTheNormalKeyIsNoLongerAccepted() = runTest {
        val rescue = signedByRecovery(version = 2)
        val store = FakeStore(envelope = rescue)
        val stolen = signed(version = 99)
        assertEquals(EndpointUpdateResult.FAILED, updater(store, mapOf(PRIMARY to stolen), recoverableVerifier).updateIfDue())
        assertEquals(rescue, store.envelope)
    }

    @Test
    fun recoveryFilesAreVersionedAmongThemselves() = runTest {
        val store = FakeStore(envelope = signedByRecovery(version = 5))
        assertEquals(EndpointUpdateResult.FAILED, updater(store, mapOf(PRIMARY to signedByRecovery(version = 4)), recoverableVerifier).updateIfDue())
        store.lastCheck = null
        assertEquals(EndpointUpdateResult.UNCHANGED, updater(store, mapOf(PRIMARY to signedByRecovery(version = 5)), recoverableVerifier).updateIfDue())
        store.lastCheck = null
        assertEquals(EndpointUpdateResult.UPDATED, updater(store, mapOf(PRIMARY to signedByRecovery(version = 6)), recoverableVerifier).updateIfDue())
    }

    /** A release ends the recovery phase by raising the floor above the recovery file. */
    @Test
    fun aHigherFloorDropsTheRecoveryFileSoTheNormalKeyWorksAgain() = runTest {
        val floored = EndpointFileVerifier(TestKeys.PUBLIC, TestKeys.OTHER_PUBLIC, minVersion = 3)
        val store = FakeStore(envelope = signedByRecovery(version = 2))
        val next = signed(version = 3)
        assertEquals(EndpointUpdateResult.UPDATED, updater(store, mapOf(PRIMARY to next), floored).updateIfDue())
        assertEquals(next, store.envelope)
    }

    @Test
    fun oversizeAnswerIsRejected() = runTest {
        val store = FakeStore()
        val huge = " ".repeat(EndpointFileVerifier.MAX_ENVELOPE_BYTES) + signed()
        assertEquals(EndpointUpdateResult.FAILED, updater(store, mapOf(PRIMARY to huge)).updateIfDue())
        assertNull(store.envelope)
    }

    private companion object {
        const val PRIMARY = "https://primary.test/endpoints.json"
        const val FALLBACK = "https://fallback.test/endpoints.json"
    }
}
