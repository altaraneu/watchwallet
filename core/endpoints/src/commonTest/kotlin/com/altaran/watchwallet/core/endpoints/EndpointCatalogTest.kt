package com.altaran.watchwallet.core.endpoints

import com.altaran.watchwallet.core.model.ChainId
import kotlin.test.Test
import kotlin.test.assertEquals

class EndpointCatalogTest {
    private val bitcoin = ChainId.BITCOIN
    private val bundled = BundledEndpoints.urls(bitcoin, EndpointProtocol.ESPLORA)
    private val remote = listOf("https://new.test/api")

    private fun resolve(stored: String?, autoUpdate: Boolean = true, custom: String? = null, now: Long = NOW) =
        EndpointCatalog.resolve(stored, autoUpdate, custom, now, verifier)

    private fun EndpointCatalog.esplora() = urls(bitcoin, EndpointProtocol.ESPLORA)

    @Test
    fun offlineWithNothingStoredUsesBundled() {
        assertEquals(bundled, resolve(null).esplora())
    }

    @Test
    fun everyBundledEntryIsAvailable() {
        BundledEndpoints.ENTRIES.forEach { entry ->
            assertEquals(entry.urls, EndpointCatalog.BUNDLED.urls(ChainId(entry.chain), entry.protocol))
        }
    }

    @Test
    fun validFileOverlaysOnlyItsEntries() {
        val catalog = resolve(signed())
        assertEquals(remote, catalog.esplora())
        assertEquals(BundledEndpoints.urls(ChainId("xrp"), EndpointProtocol.XRPL), catalog.urls(ChainId("xrp"), EndpointProtocol.XRPL))
    }

    @Test
    fun badStoredFileUsesBundled() {
        assertEquals(bundled, resolve("garbage").esplora())
        assertEquals(bundled, EndpointCatalog.resolve(signed(), true, null, NOW, EndpointFileVerifier()).esplora())
    }

    @Test
    fun expiredFileUsesBundled() {
        assertEquals(remote, resolve(signed(expires = NOW + 1)).esplora())
        assertEquals(bundled, resolve(signed(expires = NOW)).esplora())
        assertEquals(bundled, resolve(signed(expires = NOW - 1)).esplora())
    }

    @Test
    fun customServerWinsOverBundledAndRemote() {
        val custom = "http://umbrel.local:3006/api"
        assertEquals(listOf(custom), resolve(null, custom = custom).esplora())
        assertEquals(listOf(custom), resolve(signed(), custom = custom).esplora())
        assertEquals(listOf(custom), resolve(signed(), autoUpdate = false, custom = custom).esplora())
    }

    @Test
    fun toggleOffUsesBundledOnly() {
        assertEquals(bundled, resolve(signed(), autoUpdate = false).esplora())
    }

    @Test
    fun remoteCanAddBlockscoutWhereNoneIsBundled() {
        val evm56 = ChainId.evm(56)
        assertEquals(null, EndpointCatalog.BUNDLED.firstOrNull(evm56, EndpointProtocol.BLOCKSCOUT))
        val catalog = resolve(signed(entries = listOf(entry("evm:56", "blockscout", "https://bsc-scout.test"))))
        assertEquals("https://bsc-scout.test", catalog.firstOrNull(evm56, EndpointProtocol.BLOCKSCOUT))
    }
}
