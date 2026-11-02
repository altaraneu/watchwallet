package com.altaran.watchwallet.core.endpoints

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EndpointHostNoticeTest {
    private class FakeHosts(var seen: Set<String>? = null) : EndpointHostsStore {
        override suspend fun seenHosts() = seen

        override suspend fun saveSeenHosts(hosts: Set<String>) {
            seen = hosts
        }
    }

    private val bundled = EndpointCatalog.resolve(null, true, null, NOW, verifier)
    private val updated = EndpointCatalog.resolve(signed(), true, null, NOW, verifier)

    @Test
    fun theFirstStartOnlyRecordsTheHosts() = runTest {
        val store = FakeHosts()
        assertNull(EndpointHostNotice(bundled, store).pending())
        assertEquals(bundled.listHosts, store.seen)
    }

    @Test
    fun aChangeIsReportedWithWhatCameAndWent() = runTest {
        val change = EndpointHostNotice(updated, FakeHosts(bundled.listHosts)).pending()!!
        assertEquals(listOf("new.test"), change.added)
        assertEquals(listOf("blockstream.info", "mempool.space"), change.removed)
    }

    @Test
    fun theNoticeStaysUntilAcknowledgedAndThenGoesForGood() = runTest {
        val store = FakeHosts(bundled.listHosts)
        val notice = EndpointHostNotice(updated, store)
        assertEquals(notice.pending(), notice.pending(), "still there after a restart without seeing it")
        notice.acknowledge()
        assertNull(notice.pending())
        assertNull(EndpointHostNotice(updated, store).pending(), "not again at the next start")
    }

    @Test
    fun noChangeNoNotice() = runTest {
        assertNull(EndpointHostNotice(bundled, FakeHosts(bundled.listHosts)).pending())
    }

    @Test
    fun theUsersOwnServerIsNoChange() = runTest {
        val custom = EndpointCatalog.resolve(null, true, "http://umbrel.local:3006/api", NOW, verifier)
        assertNull(EndpointHostNotice(custom, FakeHosts(bundled.listHosts)).pending())
    }

    @Test
    fun goingBackToTheBuiltInServersIsAlsoReported() = runTest {
        val change = EndpointHostNotice(bundled, FakeHosts(updated.listHosts)).pending()!!
        assertEquals(listOf("new.test"), change.removed)
    }
}
