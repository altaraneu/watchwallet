package com.altaran.watchwallet.core.endpoints

import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EndpointListApprovalTest {
    private val bundled = BundledEndpoints.urls(ChainId.BITCOIN, EndpointProtocol.ESPLORA)
    private var now = NOW

    private fun approval(store: FakeStore) = EndpointListApproval(store, verifier) { now }

    /** One update check a day later than the last, serving [served]. */
    private suspend fun check(store: FakeStore, served: String): EndpointUpdateResult {
        store.lastCheck = null
        val engine = MockEngine { respond(served) }
        return EndpointConfigUpdater(createHttpClient(engine = engine), store, verifier, listOf("https://primary.test/f"), { now }).updateIfDue()
    }

    /** What the app would use after a restart: the catalog sees only the applied list. */
    private fun serversAfterRestart(store: FakeStore) =
        EndpointCatalog.resolve(store.envelope, store.enabled, null, now, verifier).urls(ChainId.BITCOIN, EndpointProtocol.ESPLORA)

    @Test
    fun aListThatAddsAHostWaitsAndChangesNothingUntilApproved() = runTest {
        val store = FakeStore()
        assertEquals(EndpointUpdateResult.AWAITING_APPROVAL, check(store, signed()))
        assertNull(store.envelope)
        assertEquals(bundled, serversAfterRestart(store))
        assertEquals(listOf("new.test"), approval(store).pending()?.added)
    }

    @Test
    fun theQuestionSurvivesARestartAndAnUnansweredDialog() = runTest {
        val store = FakeStore()
        check(store, signed())
        assertEquals(approval(store).pending(), approval(store).pending())
        assertNotNull(approval(store).pending(), "a dialog closed without an answer asks again at the next start")
        assertEquals(bundled, serversAfterRestart(store))
    }

    @Test
    fun approvingAppliesAtTheNextStartAndOnlyOnce() = runTest {
        val store = FakeStore()
        check(store, signed())
        approval(store).approve(approval(store).pending()!!)
        assertEquals(signed(), store.envelope)
        assertNull(store.pending)
        assertEquals(listOf("https://new.test/api"), serversAfterRestart(store))
        assertNull(approval(store).pending())
    }

    @Test
    fun aDeclinedListStaysOutAndIsNotOfferedAgainForThatVersion() = runTest {
        val store = FakeStore()
        check(store, signed(version = 5))
        approval(store).decline(approval(store).pending()!!)
        assertNull(store.envelope)
        assertEquals(bundled, serversAfterRestart(store))

        assertEquals(EndpointUpdateResult.UNCHANGED, check(store, signed(version = 5)))
        assertNull(approval(store).pending())
    }

    @Test
    fun aHigherVersionMayAskAgainAfterADecline() = runTest {
        val store = FakeStore()
        check(store, signed(version = 5))
        approval(store).decline(approval(store).pending()!!)
        assertEquals(EndpointUpdateResult.AWAITING_APPROVAL, check(store, signed(version = 6)))
        assertEquals(6, approval(store).pending()?.version)
    }

    /** Otherwise declining a list would reopen the door to replays of the ones before it. */
    @Test
    fun aDeclinedVersionStillRejectsOlderFiles() = runTest {
        val store = FakeStore()
        check(store, signed(version = 5))
        approval(store).decline(approval(store).pending()!!)
        assertEquals(EndpointUpdateResult.FAILED, check(store, signed(version = 4)))
        assertNull(store.pending)
    }

    @Test
    fun aWaitingVersionAlsoRejectsOlderFilesAndIsNotReplacedByItself() = runTest {
        val store = FakeStore()
        check(store, signed(version = 5))
        assertEquals(EndpointUpdateResult.FAILED, check(store, signed(version = 4)))
        assertEquals(EndpointUpdateResult.UNCHANGED, check(store, signed(version = 5)))
        assertEquals(signed(version = 5), store.pending)
    }

    @Test
    fun aListThatOnlyRemovesHostsNeedsNoQuestion() = runTest {
        val store = FakeStore()
        assertEquals(EndpointUpdateResult.UPDATED, check(store, quiet()))
        assertEquals(quiet(), store.envelope)
        assertEquals(1, serversAfterRestart(store).size)
        assertNull(approval(store).pending())
    }

    @Test
    fun aListThatChangesNoHostNeedsNoQuestion() = runTest {
        val store = FakeStore()
        assertEquals(EndpointUpdateResult.UPDATED, check(store, signed(entries = listOf(entry(urls = bundled.toTypedArray())))))
        assertNull(approval(store).pending())
        assertEquals(bundled, serversAfterRestart(store))
    }

    /** The list is what is approved: the next one comparing against it needs no second question. */
    @Test
    fun hostsAlreadyApprovedAreNotAskedForAgain() = runTest {
        val store = FakeStore()
        check(store, signed(version = 5))
        approval(store).approve(approval(store).pending()!!)
        assertEquals(EndpointUpdateResult.UPDATED, check(store, signed(version = 6)))
        assertEquals(signed(version = 6), store.envelope)
    }

    @Test
    fun aHostOnlyTheNewListNamesIsListedAloneWithItsPort() = runTest {
        val store = FakeStore(envelope = signed(version = 5))
        val list = signed(version = 6, entries = listOf(entry(urls = arrayOf("https://new.test/api", "https://Other.test:8443/api"))))
        check(store, list)
        assertEquals(listOf("other.test:8443"), approval(store).pending()?.added)
        assertEquals(listOf("https://new.test/api"), serversAfterRestart(store), "still what was approved")
    }

    /** A known host taking over another chain would learn addresses it does not see today. */
    @Test
    fun aHostKnownFromAnotherChainStillAsks() = runTest {
        val store = FakeStore()
        check(store, signed(entries = listOf(entry("solana", EndpointProtocol.SOLANA_RPC, "https://mempool.space/rpc"))))
        assertEquals(listOf("mempool.space"), approval(store).pending()?.added)
    }

    @Test
    fun aDifferentPortOnAKnownHostAsks() = runTest {
        val store = FakeStore()
        check(store, signed(entries = listOf(entry(urls = arrayOf("https://mempool.space:8443/api")))))
        assertEquals(listOf("mempool.space:8443"), approval(store).pending()?.added)
    }

    /** Going back to servers shipped with the app needs nobody's consent; a rescue must not wait. */
    @Test
    fun goingBackToBuiltInHostsNeedsNoQuestion() = runTest {
        val store = FakeStore(envelope = signed(version = 5))
        assertEquals(EndpointUpdateResult.UPDATED, check(store, quiet(version = 6)))
        assertEquals(quiet(version = 6), store.envelope)
    }

    @Test
    fun aListThatExpiresWhileWaitingIsDroppedButStillCountsForReplays() = runTest {
        val store = FakeStore()
        check(store, signed(version = 5, expires = LATER))
        now = LATER
        assertNull(approval(store).pending())
        assertNull(store.pending)
        assertNull(store.envelope)
        assertEquals(bundled, serversAfterRestart(store))
        assertEquals(EndpointUpdateResult.FAILED, check(store, signed(version = 4, expires = LATER + 1)))
    }

    @Test
    fun approvingAListThatJustExpiredDoesNothing() = runTest {
        val store = FakeStore()
        check(store, signed(expires = LATER))
        val shown = approval(store).pending()!!
        now = LATER
        approval(store).approve(shown)
        assertNull(store.envelope)
        assertEquals(bundled, serversAfterRestart(store))
    }

    /** Expired means back to the built-in servers, so those are what a new list is compared with. */
    @Test
    fun afterTheAppliedListExpiredItsHostsNeedApprovalAgain() = runTest {
        val store = FakeStore(envelope = signed(version = 5, expires = LATER))
        now = LATER
        assertEquals(bundled, serversAfterRestart(store))
        assertEquals(EndpointUpdateResult.AWAITING_APPROVAL, check(store, signed(version = 6, expires = LATER + 100)))
        assertEquals(listOf("new.test"), approval(store).pending()?.added)
        assertEquals(bundled, serversAfterRestart(store))
    }

    /** The dialog shows one list while a newer one arrives: the answer belongs to what was shown. */
    @Test
    fun anAnswerDoesNotApplyToANewerListBehindTheDialog() = runTest {
        val store = FakeStore()
        check(store, signed(version = 5))
        val shown = approval(store).pending()!!
        check(store, signed(version = 6, entries = listOf(entry(urls = arrayOf("https://evil.test/api")))))

        approval(store).approve(shown)
        assertNull(store.envelope)
        approval(store).decline(shown)
        assertNull(store.declined)
        assertEquals(listOf("evil.test"), approval(store).pending()?.added)
    }

    @Test
    fun aNewerListWithoutNewHostsReplacesAWaitingOne() = runTest {
        val store = FakeStore()
        check(store, signed(version = 5))
        assertEquals(EndpointUpdateResult.UPDATED, check(store, quiet(version = 6)))
        assertNull(store.pending)
        assertEquals(quiet(version = 6), store.envelope)
    }

    @Test
    fun nothingIsAskedWhileUpdatesAreOff() = runTest {
        val store = FakeStore()
        check(store, signed())
        store.enabled = false
        assertNull(approval(store).pending())
        assertNotNull(store.pending, "kept for when they are turned on again")
        assertEquals(bundled, serversAfterRestart(store))
    }

    /** What was approved stays approved: switching off and on again needs no new answer. */
    @Test
    fun turningUpdatesOffUsesBuiltInServersAndOnAgainTheApprovedList() = runTest {
        val store = FakeStore(envelope = signed())
        store.enabled = false
        assertEquals(bundled, serversAfterRestart(store))
        store.enabled = true
        assertEquals(listOf("https://new.test/api"), serversAfterRestart(store))
    }

    @Test
    fun aRecoveryListThatAddsAHostAlsoWaits() = runTest {
        val store = FakeStore(envelope = signed(version = Long.MAX_VALUE))
        val rescue = signedByRecovery(version = 2, entries = listOf(entry(urls = arrayOf("https://rescue.test/api"))))
        val updater =
            EndpointConfigUpdater(createHttpClient(engine = MockEngine { respond(rescue) }), store, recoverableVerifier, listOf("https://p.test/f"), { now })
        assertEquals(EndpointUpdateResult.AWAITING_APPROVAL, updater.updateIfDue())
        assertEquals(signed(version = Long.MAX_VALUE), store.envelope)
    }

    @Test
    fun aDamagedWaitingFileAsksNothing() = runTest {
        val store = FakeStore(pending = "garbage")
        assertNull(approval(store).pending())
        assertNull(store.pending)
    }
}
