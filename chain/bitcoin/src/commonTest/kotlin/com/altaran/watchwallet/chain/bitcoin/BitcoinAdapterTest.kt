package com.altaran.watchwallet.chain.bitcoin

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
        val result = assertNotNull(toChainTransaction(tx("a", listOf("bc1qother" to 60_000), listOf(ours to 50_000, "bc1qother" to 9_000), fee = 1_000), setOf(ours)))
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
                request.url.encodedPath.endsWith("/address/$address") ->
                    """{"address":"$address","chain_stats":{"funded_txo_sum":150000,"spent_txo_sum":50000,"tx_count":2},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}"""
                request.url.encodedPath.endsWith("/txs") ->
                    """[{"txid":"t2","status":{"confirmed":true,"block_time":1700000100},"fee":200,"vin":[{"prevout":{"scriptpubkey_address":"$address","value":150000}}],"vout":[{"scriptpubkey_address":"bc1qx","value":49800},{"scriptpubkey_address":"$address","value":100000}]},
                       {"txid":"t1","status":{"confirmed":true,"block_time":1700000000},"fee":100,"vin":[{"prevout":{"scriptpubkey_address":"bc1qy","value":200000}}],"vout":[{"scriptpubkey_address":"$address","value":150000}]}]"""
                else -> error("Unexpected ${request.url}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val adapter = BitcoinAdapter(createHttpClient(engine = engine), "https://esplora.test/api")
        val result = adapter.sync(WatchTarget.Address(ChainFamily.UTXO, ChainId.BITCOIN, address, ScriptType.P2WPKH), emptySet())
        assertEquals(100_000, result.balances.single().amount.raw.longValue())
        assertEquals(listOf(-50_000L, 150_000L), result.transactions.map { it.delta.longValue() })
        assertEquals(listOf(ChainId.BITCOIN), result.activeChains)
    }
}
