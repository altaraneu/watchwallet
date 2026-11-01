package com.altaran.watchwallet.chain.evm

import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvmAdapterTest {
    private val address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val own = address.lowercase()

    private val mainnet = EvmNetwork(1, "Ethereum", "ETH", "Ether", "ethereum", "https://rpc.test/1", "https://bs.test", "https://etherscan.io", "ethereum", 0)
    private val quiet = EvmNetwork(56, "BNB", "BNB", "BNB", "binancecoin", "https://rpc.test/56", null, "https://bscscan.com", null, 0)

    private fun MockRequestHandleScope.json(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private val engine = MockEngine { request ->
        val url = request.url.toString()
        when {
            url.startsWith("https://rpc.test/1") -> {
                val body = request.body.toByteArray().decodeToString()
                if ("eth_getBalance" in body) json("""{"result":"0xde0b6b3a7640000"}""") else json("""{"result":"0x2"}""")
            }

            url.startsWith("https://rpc.test/56") -> {
                json("""{"result":"0x0"}""")
            }

            url.endsWith("/counters") -> {
                json("""{"transactions_count":"2","token_transfers_count":"1"}""")
            }

            url.contains("/token-balances") -> {
                json(
                    """[{"token":{"address_hash":"0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48","symbol":"USDC","name":"USD Coin","decimals":"6","type":"ERC-20"},"value":"2500000"},
                   {"token":{"address_hash":"0x1111111111111111111111111111111111111111","symbol":"NFT","name":"x","decimals":null,"type":"ERC-721"},"value":"1"}]""",
                )
            }

            url.contains("/internal-transactions") -> {
                json("""{"items":[],"next_page_params":null}""")
            }

            url.contains("/token-transfers") -> {
                json(
                    """{"items":[{"transaction_hash":"0xt3","timestamp":"2024-03-01T00:00:00.000000Z","from":{"hash":"0xexchange"},"to":{"hash":"$own"},
                   "total":{"value":"2500000","decimals":"6"},"token":{"address_hash":"0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48","symbol":"USDC","name":"USD Coin","decimals":"6","type":"ERC-20"}}],"next_page_params":null}""",
                )
            }

            url.contains("/transactions") -> {
                json(
                    """{"items":[
                   {"hash":"0xt2","timestamp":"2024-02-01T00:00:00.000000Z","from":{"hash":"$own"},"to":{"hash":"0xshop"},"value":"1000000000000000000","fee":{"value":"21000000000000"},"status":"ok","result":"success"},
                   {"hash":"0xt1","timestamp":"2024-01-01T00:00:00.000000Z","from":{"hash":"0xexchange"},"to":{"hash":"$own"},"value":"2000000000000000000","fee":{"value":"1"},"status":"ok","result":"success"}
                   ],"next_page_params":null}""",
                )
            }

            else -> {
                error("Unexpected $url")
            }
        }
    }

    private val adapter = EvmAdapter(createHttpClient(engine = engine), listOf(mainnet, quiet))
    private val target = WatchTarget.Address(ChainFamily.EVM, ChainId.evm(1), address)

    @Test
    fun probeFindsOnlyActiveNetworks() = runTest {
        withContext(Dispatchers.Default) {
            val probe = adapter.probe(target)
            assertTrue(probe.hasActivity)
            assertEquals(listOf(ChainId.evm(1)), probe.activeChains)
        }
    }

    @Test
    fun syncReturnsNativeTokensAndHistory() = runTest {
        withContext(Dispatchers.Default) {
            val result = adapter.sync(target, emptySet())
            assertEquals(listOf("ETH", "USDC"), result.balances.map { it.asset.symbol }, "ERC-721 is ignored")
            assertEquals("1", result.balances.first().amount.toPlainString())
            assertEquals("2.5", result.balances[1].amount.toPlainString())

            val byHash = result.transactions.associateBy { it.hash }
            assertEquals("-1000021000000000000", byHash.getValue("0xt2").delta.toString(), "value + fee")
            assertEquals("2000000000000000000", byHash.getValue("0xt1").delta.toString())
            assertEquals("2500000", byHash.getValue("0xt3").delta.toString())
            assertEquals("token:ethereum:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", byHash.getValue("0xt3").asset.priceId)
        }
    }

    @Test
    fun spamHeuristics() {
        assertTrue(SpamFilter.isSpam("USDC", "Visit usdc-claim.com to claim"))
        assertTrue(SpamFilter.isSpam("\$ 1000 ETH", "Reward"))
        assertFalse(SpamFilter.isSpam("USDC", "USD Coin"))
    }
}

class EvmRpcRequestTest {
    @Test
    fun requestAlwaysCarriesJsonRpcVersionAndId() {
        val body = com.altaran.watchwallet.core.network.NetworkJson.encodeToString(
            RpcRequest.serializer(),
            RpcRequest(method = "eth_getBalance", params = listOf("0x0", "latest")),
        )
        kotlin.test.assertTrue("\"jsonrpc\":\"2.0\"" in body && "\"id\":1" in body, body)
    }
}
