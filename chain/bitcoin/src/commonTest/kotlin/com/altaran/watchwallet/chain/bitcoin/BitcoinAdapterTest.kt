package com.altaran.watchwallet.chain.bitcoin

import com.altaran.watchwallet.chain.api.SyncOptions
import com.altaran.watchwallet.core.crypto.BitcoinAddresses
import com.altaran.watchwallet.core.crypto.ExtendedKeys
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitcoinAdapterTest {
    private val ours = "bc1qours"
    private val change = "bc1qchange"

    private fun tx(id: String, vinFrom: List<Pair<String, Long>>, vout: List<Pair<String, Long>>, fee: Long = 0) = EsploraTx(
        txid = id,
        status = EsploraStatus(confirmed = true, blockTime = 1_700_000_000),
        fee = fee,
        vin = vinFrom.map { EsploraVin(EsploraVout(it.first, it.second)) },
        vout = vout.map { EsploraVout(it.first, it.second) },
    )

    @Test
    fun incomingPayment() {
        val result =
            assertNotNull(toChainTransaction(tx("a", listOf("bc1qother" to 60_000), listOf(ours to 50_000, "bc1qother" to 9_000), fee = 1_000), setOf(ours)))
        assertEquals(50_000, result.delta.longValue())
        assertNull(result.fee, "fee is paid by the sender")
        assertTrue(!result.isSelfTransfer)
    }

    @Test
    fun outgoingPaymentWithChangeIncludesFee() {
        val result = assertNotNull(
            toChainTransaction(tx("b", listOf(ours to 100_000), listOf("bc1qshop" to 30_000, change to 69_000), fee = 1_000), setOf(ours, change)),
        )
        assertEquals(-31_000, result.delta.longValue(), "30k sent + 1k fee")
        assertEquals(1_000, result.fee?.longValue())
    }

    @Test
    fun selfTransferOnlyCostsTheFee() {
        val result = assertNotNull(toChainTransaction(tx("c", listOf(ours to 10_000), listOf(change to 9_500), fee = 500), setOf(ours, change)))
        assertEquals(-500, result.delta.longValue())
        assertTrue(result.isSelfTransfer)
    }

    @Test
    fun unrelatedTransactionIsIgnored() {
        assertNull(toChainTransaction(tx("d", listOf("x" to 1), listOf("y" to 1)), setOf(ours)))
    }

    @Test
    fun syncSingleAddressWithMockedEsplora() = runTest {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val engine = MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/address/$address") -> {
                    """{"address":"$address","chain_stats":{"funded_txo_sum":150000,"spent_txo_sum":50000,"tx_count":2},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}"""
                }

                request.url.encodedPath.endsWith("/txs") -> {
                    """[{"txid":"t2","status":{"confirmed":true,"block_time":1700000100},"fee":200,"vin":[{"prevout":{"scriptpubkey_address":"$address","value":150000}}],"vout":[{"scriptpubkey_address":"bc1qx","value":49800},{"scriptpubkey_address":"$address","value":100000}]},
                       {"txid":"t1","status":{"confirmed":true,"block_time":1700000000},"fee":100,"vin":[{"prevout":{"scriptpubkey_address":"bc1qy","value":200000}}],"vout":[{"scriptpubkey_address":"$address","value":150000}]}]"""
                }

                else -> {
                    error("Unexpected ${request.url}")
                }
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val adapter = BitcoinAdapter(createHttpClient(engine = engine), "https://esplora.test/api")
        val result = adapter.sync(WatchTarget.Address(ChainFamily.UTXO, ChainId.BITCOIN, address, ScriptType.P2WPKH), emptySet())
        assertEquals(100_000, result.balances.single().amount.raw.longValue())
        assertEquals(listOf(-50_000L, 150_000L), result.transactions.map { it.delta.longValue() })
        assertEquals(listOf(ChainId.BITCOIN), result.activeChains)
    }

    @Test
    fun failingEsploraInstanceFallsBackToTheNextOne() = runTest {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val hosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            hosts += request.url.host
            if (request.url.host == "down.test") error("timeout")
            val body = if (request.url.encodedPath.endsWith("/txs")) {
                "[]"
            } else {
                """{"address":"$address","chain_stats":{"funded_txo_sum":7,"spent_txo_sum":0,"tx_count":0},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val adapter = BitcoinAdapter(createHttpClient(engine = engine), listOf("https://down.test/api", "https://up.test/api"))
        val target = WatchTarget.Address(ChainFamily.UTXO, ChainId.BITCOIN, address, ScriptType.P2WPKH)
        assertEquals(7, adapter.sync(target, emptySet()).balances.single().amount.raw.longValue())
        // The working instance is remembered: the last calls go straight to it.
        assertEquals(listOf("down.test", "up.test"), hosts.distinct())
        assertEquals("up.test", hosts.last())
    }

    @Test
    fun continueHistoryFetchesOlderTransactionsPastKnownOnes() = runTest {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        // 60 transactions, newest first: pages of 25, 25 and 10.
        val all = (60 downTo 1).map { "t$it" }
        fun txJson(id: String) =
            """{"txid":"$id","status":{"confirmed":true,"block_time":${1_700_000_000 + id.drop(
                1,
            ).toInt()}},"fee":1,"vin":[{"prevout":{"scriptpubkey_address":"bc1qy","value":1000}}],"vout":[{"scriptpubkey_address":"$address","value":999}]}"""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/address/$address") -> {
                    """{"address":"$address","chain_stats":{"funded_txo_sum":59940,"spent_txo_sum":0,"tx_count":60},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}"""
                }

                else -> {
                    val after = path.substringAfter("/txs/chain/", "").ifEmpty { null }
                    val start = after?.let { all.indexOf(it) + 1 } ?: 0
                    all.drop(start).take(25).joinToString(",", "[", "]") { txJson(it) }
                }
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val adapter = BitcoinAdapter(createHttpClient(engine = engine), "https://esplora.test/api")
        val target = WatchTarget.Address(ChainFamily.UTXO, ChainId.BITCOIN, address, ScriptType.P2WPKH)
        val known = all.take(25).toSet()

        val normal = adapter.sync(target, known)
        val older = all.drop(25).toSet()
        assertEquals(emptyList(), normal.transactions.map { it.hash }.filter { it in older }, "incremental sync stops at known history")

        val backfill = adapter.sync(target, known, continueHistory = true)
        assertEquals(older, backfill.transactions.map { it.hash }.toSet())
        assertEquals(false, backfill.historyTruncated)
    }

    @Test
    fun anAddressEnteringTheGapWindowIsReadToItsEnd() = runTest {
        val xpub = "xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj"
        val key = assertNotNull(ExtendedKeys.parse(xpub)).key
        val first = BitcoinAddresses.derive(key, ScriptType.P2PKH, false, 0)
        val second = BitcoinAddresses.derive(key, ScriptType.P2PKH, false, 1)

        fun txJson(id: String, vararg outputs: String) = """{"txid":"$id","status":{"confirmed":true,"block_time":1700000000},"fee":1,"vin":[],"vout":[""" +
            outputs.joinToString(",") { """{"scriptpubkey_address":"$it","value":10}""" } + "]}"
        val shared = txJson("shared", first, second)
        val histories = mapOf(first to listOf(shared), second to List(25) { if (it == 0) shared else txJson("new$it", second) } + txJson("old", second))
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val address = path.substringAfter("/address/").substringBefore('/')
            val rows = histories[address].orEmpty()
            val body = when {
                "/txs" !in path -> {
                    """{"address":"$address","chain_stats":{"funded_txo_sum":${rows.size * 10},"spent_txo_sum":0,"tx_count":${rows.size}},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}"""
                }

                else -> {
                    val after = path.substringAfter("/txs/chain/", "").ifEmpty { null }
                    val start = after?.let { id -> rows.indexOfFirst { "\"txid\":\"$id\"" in it } + 1 } ?: 0
                    rows.drop(start).take(25).joinToString(",", "[", "]")
                }
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val adapter = BitcoinAdapter(createHttpClient(engine = engine), "https://esplora.test/api")
        val target = WatchTarget.ExtendedPublicKey(ChainFamily.UTXO, ChainId.BITCOIN, xpub, ScriptType.P2PKH, gapLimit = 2)
        val result = adapter.sync(target, setOf("shared"), SyncOptions(usedAddresses = setOf(first))) { }
        assertTrue(result.transactions.any { it.hash == "old" }, "not stopped by the known transaction on its first page")
        assertEquals(listOf(20L), result.correctedTransactions.map { it.delta.longValue() }, "both outputs of the known transaction are ours")
    }
}
