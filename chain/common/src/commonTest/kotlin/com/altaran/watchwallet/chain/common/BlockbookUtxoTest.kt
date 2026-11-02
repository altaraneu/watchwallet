package com.altaran.watchwallet.chain.common

import com.altaran.watchwallet.chain.api.SyncOptions
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.Throttle
import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class BlockbookUtxoTest {
    private val chainId = ChainId("dogecoin")
    private val chain = Chain(chainId, ChainFamily.UTXO, "Dogecoin", "DOGE", 8, "dogecoin", "{tx}", "{address}", 0)
    private val asset = Asset(AssetId.native(chainId), chainId, "DOGE", "Dogecoin", 8, "dogecoin")

    private val basic = """{"page":1,"totalPages":1,"itemsOnPage":1000,"address":"DOurs","balance":"150000000","totalReceived":"250000000",
        "totalSent":"100000000","unconfirmedBalance":"-10000000","unconfirmedTxs":1,"txs":2}"""

    private val txs = """{"page":1,"totalPages":1,"itemsOnPage":50,"address":"DOurs","balance":"150000000","unconfirmedBalance":"-10000000",
        "unconfirmedTxs":1,"txs":2,"transactions":[
        {"txid":"t2","vin":[{"n":0,"addresses":["DOurs"],"isAddress":true,"value":"150000000"}],
         "vout":[{"value":"9000000","n":0,"addresses":["DShop"],"isAddress":true},{"value":"140000000","n":1,"addresses":["DOurs"],"isAddress":true}],
         "blockHeight":-1,"confirmations":0,"blockTime":1700000500,"value":"149000000","valueIn":"150000000","fees":"1000000"},
        {"txid":"t1","vin":[{"n":0,"addresses":["DExchange"],"isAddress":true,"value":"300000000"}],
         "vout":[{"value":"150000000","n":0,"addresses":["DOurs"],"isAddress":true},{"value":"149000000","n":1,"addresses":["DExchange"],"isAddress":true}],
         "blockHeight":5000000,"confirmations":10,"blockTime":1700000000,"value":"299000000","valueIn":"300000000","fees":"1000000"}]}"""

    private val engine = MockEngine { request ->
        val body = if (request.url.parameters["details"] == "txs") txs else basic
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val adapter = UtxoAdapter(
        chain,
        asset,
        BlockbookBackend(createHttpClient(engine = engine), "https://bb.test", Throttle(2, 1.milliseconds)),
        deriver = { error("not used") },
    )

    @Test
    fun syncComputesBalanceAndDeltas() = runTest {
        val result = adapter.sync(WatchTarget.Address(ChainFamily.UTXO, chainId, "DOurs"), emptySet())
        assertEquals("1.4", result.balances.single().amount.toPlainString(), "confirmed + unconfirmed")
        val byHash = result.transactions.associateBy { it.hash }
        assertEquals(-10_000_000, byHash.getValue("t2").delta.longValue(), "9 DOGE sent + 1 DOGE fee")
        assertEquals(false, byHash.getValue("t2").confirmed)
        assertEquals(150_000_000, byHash.getValue("t1").delta.longValue())
        assertEquals(null, byHash.getValue("t1").fee)
        assertEquals(listOf(chainId), result.activeChains)
        assertTrue(engine.requestHistory.all { "/api/v2/address/DOurs" in it.url.encodedPath })
    }

    /** Pages of one transaction each; [known] stops a walk like the real backends do. */
    private class FakeBackend(val histories: Map<String, List<UtxoTx>>) : UtxoBackend {
        val walked = HashMap<String, Int>()

        override suspend fun address(address: String) = UtxoAddressInfo(histories[address].orEmpty().size, histories[address].orEmpty().sumOf { 1L })

        override suspend fun history(address: String, known: Set<String>, maxPages: Int): UtxoHistory {
            val rows = histories[address].orEmpty()
            val end = rows.indexOfFirst { it.confirmed && it.txid in known }
            val seen = if (end < 0) rows else rows.take(end + 1)
            walked[address] = seen.size
            return UtxoHistory(seen, truncated = false)
        }
    }

    private fun tx(id: String, vararg outputs: String) = UtxoTx(id, 1_000, true, 0, listOf(UtxoIo("elsewhere", 100)), outputs.map { UtxoIo(it, 10) })

    @Test
    fun anAddressEnteringTheGapWindowIsReadToItsEnd() = runTest {
        val shared = tx("shared", "m0", "m1")
        val older = tx("older", "m1")
        val backend = FakeBackend(mapOf("m0" to listOf(shared), "m1" to listOf(shared, older)))
        val target = WatchTarget.ExtendedPublicKey(ChainFamily.UTXO, chainId, "xpub", com.altaran.watchwallet.core.model.ScriptType.P2PKH, gapLimit = 2)
        val adapter = UtxoAdapter(chain, asset, backend, deriver = { { change, index -> if (change) "c$index" else "m$index" } })

        val result = adapter.sync(target, setOf("shared"), SyncOptions(usedAddresses = setOf("m0"))) { }

        assertEquals(1, backend.walked["m0"], "a known address stops at the known transaction")
        assertEquals(2, backend.walked["m1"], "the new one does not")
        assertEquals(listOf("older"), result.transactions.map { it.hash })
        assertEquals(listOf("shared"), result.correctedTransactions.map { it.hash })
        assertEquals(20, result.correctedTransactions.single().delta.longValue(), "both outputs are ours now")
    }

    @Test
    fun nothingIsCorrectedWhenAllAddressesWereKnown() = runTest {
        val shared = tx("shared", "m0")
        val backend = FakeBackend(mapOf("m0" to listOf(shared, tx("older", "m0"))))
        val target = WatchTarget.ExtendedPublicKey(ChainFamily.UTXO, chainId, "xpub", com.altaran.watchwallet.core.model.ScriptType.P2PKH, gapLimit = 2)
        val adapter = UtxoAdapter(chain, asset, backend, deriver = { { change, index -> if (change) "c$index" else "m$index" } })
        val result = adapter.sync(target, setOf("shared"), SyncOptions(usedAddresses = setOf("m0"))) { }
        assertEquals(1, backend.walked["m0"])
        assertTrue(result.correctedTransactions.isEmpty())
    }
}
