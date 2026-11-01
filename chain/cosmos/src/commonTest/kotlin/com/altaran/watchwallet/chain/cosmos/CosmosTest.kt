package com.altaran.watchwallet.chain.cosmos

import com.altaran.watchwallet.chain.common.Bech32Codec
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
import kotlin.test.assertTrue

class CosmosTest {
    private val key = ByteArray(20) { (it * 7 + 3).toByte() }
    private val ours = Bech32Codec.encodeBytes("cosmos", key)

    @Test
    fun addressValidation() {
        val cases = listOf(
            "cosmos1jv65s3grqf6v6jl3dp4t6c9t9rk99cd88lyufl" to CosmosChains.COSMOS_HUB.id, // distribution module account
            ours to CosmosChains.COSMOS_HUB.id,
            Bech32Codec.encodeBytes("osmo", key) to CosmosChains.OSMOSIS.id,
            Bech32Codec.encodeBytes("celestia", key) to CosmosChains.CELESTIA.id,
            Bech32Codec.encodeBytes("inj", key) to CosmosChains.INJECTIVE.id,
            Bech32Codec.encodeBytes("cosmos", ByteArray(32) { 1 }) to CosmosChains.COSMOS_HUB.id, // module / ICA account
            ours.dropLast(1) + (if (ours.last() == 'q') "p" else "q") to null, // wrong checksum
            Bech32Codec.encodeBytes("cosmos", ByteArray(19)) to null, // wrong length
            Bech32Codec.encodeBytes("juno", key) to null, // unsupported chain
            Bech32Codec.encodeBytes("cosmosvaloper", key) to null, // validator operator, not an account
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu" to null, // Bitcoin
        )
        cases.forEach { (input, chain) -> assertEquals(chain, CosmosAddresses.parse(input)?.chainId, input) }
    }

    @Test
    fun relatedAddressesOnlyForCoinType118() {
        val related = CosmosAddresses.relatedAddresses(ours).associate { it.chainId to it.address }
        assertEquals(setOf(CosmosChains.OSMOSIS.id, CosmosChains.CELESTIA.id), related.keys)
        assertEquals(Bech32Codec.encodeBytes("osmo", key), related[CosmosChains.OSMOSIS.id])
        assertTrue(CosmosAddresses.relatedAddresses(Bech32Codec.encodeBytes("inj", key)).isEmpty())
    }

    @Test
    fun coinLists() {
        assertEquals("1500", coinAmount("1000uatom,7ibc/27394FB092D2ECCD56123C74F36E4C1F926001CEADA9CA97EA622B25F41E5EB2,500uatom", "uatom").toString())
    }

    @Test
    fun syncReportsLiquidStakedAndHistory() = runTest {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path == "/cosmoshub/cosmos/bank/v1beta1/balances/$ours" ->
                    """{"balances":[{"denom":"ibc/27394FB092D2ECCD56123C74F36E4C1F926001CEADA9CA97EA622B25F41E5EB2","amount":"5"},{"denom":"uatom","amount":"2500000"}],
                       "pagination":{"next_key":null,"total":"2"}}"""
                path == "/cosmoshub/cosmos/staking/v1beta1/delegations/$ours" ->
                    """{"delegation_responses":[{"delegation":{"delegator_address":"$ours","validator_address":"cosmosvaloper1x","shares":"10000000.0"},
                       "balance":{"denom":"uatom","amount":"10000000"}}],"pagination":{"next_key":null,"total":"1"}}"""
                path.endsWith("/unbonding_delegations") ->
                    """{"unbonding_responses":[{"delegator_address":"$ours","validator_address":"cosmosvaloper1y","entries":[{"creation_height":"1","completion_time":"2024-06-01T00:00:00Z","initial_balance":"1000000","balance":"1000000"}]}]}"""
                path == "/cosmoshub/cosmos/tx/v1beta1/txs" && "sender" in request.url.parameters["query"].orEmpty() ->
                    """{"txs":[],"tx_responses":[{"height":"20000000","txhash":"AA11","code":0,"timestamp":"2024-05-01T10:00:00Z",
                       "tx":{"body":{"messages":[{"@type":"/cosmos.bank.v1beta1.MsgSend","from_address":"$ours","to_address":"cosmos1other","amount":[{"denom":"uatom","amount":"1000000"}]}]},
                         "auth_info":{"fee":{"amount":[{"denom":"uatom","amount":"5000"}],"gas_limit":"200000","payer":"","granter":""}}},
                       "events":[{"type":"coin_spent","attributes":[{"key":"spender","value":"$ours","index":true},{"key":"amount","value":"5000uatom","index":true}]},
                         {"type":"coin_spent","attributes":[{"key":"spender","value":"$ours","index":true},{"key":"amount","value":"1000000uatom","index":true}]},
                         {"type":"coin_received","attributes":[{"key":"receiver","value":"cosmos1other","index":true},{"key":"amount","value":"1000000uatom","index":true}]}]}],
                       "pagination":null,"total":"1"}"""
                path == "/cosmoshub/cosmos/tx/v1beta1/txs" ->
                    """{"txs":[],"tx_responses":[{"height":"19000000","txhash":"BB22","code":0,"timestamp":"2024-04-01T10:00:00Z",
                       "tx":{"body":{"messages":[{"@type":"/cosmos.bank.v1beta1.MsgSend","from_address":"cosmos1other","to_address":"$ours"}]},"auth_info":{"fee":{"amount":[]}}},
                       "events":[{"type":"coin_received","attributes":[{"key":"receiver","value":"$ours"},{"key":"amount","value":"3500000uatom"}]}]}],"total":"1"}"""
                else -> error("Unexpected $path")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val adapter = CosmosAdapter(createHttpClient(engine = engine), "https://rest.test")
        val result = adapter.sync(assertNotNull(CosmosAddresses.parse(ours)), emptySet())
        assertEquals("2.5", result.balances[0].amount.toPlainString())
        assertEquals("11", result.balances[1].amount.toPlainString(), "10 delegated + 1 unbonding")
        assertEquals("cosmos", result.balances[1].asset.priceId)
        assertTrue("staked" in result.balances[1].asset.name)

        val byHash = result.transactions.associateBy { it.hash }
        assertEquals(-1_005_000L, byHash.getValue("AA11").delta.longValue())
        assertEquals(5_000L, byHash.getValue("AA11").fee?.longValue())
        assertEquals(3_500_000L, byHash.getValue("BB22").delta.longValue())
        assertEquals(false, result.historyTruncated)
    }
}
