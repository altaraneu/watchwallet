// Fixtures contain long real addresses and JSON payloads.
@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength")

package com.altaran.watchwallet.chain.solana

import com.altaran.watchwallet.chain.api.SyncOptions
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SolanaTest {
    private val owner = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val other = "4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T"
    private val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    @Test
    fun addressValidation() {
        val cases = listOf(
            owner to true,
            "11111111111111111111111111111111" to true, // System program (32 zero bytes)
            "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA" to true,
            usdc to true,
            "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtA" to false, // too short
            "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM1" to false, // too long
            "0WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM" to false, // not base58
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed" to false, // EVM
            "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t" to false, // Tron, 25 bytes
        )
        cases.forEach { (input, valid) -> assertEquals(valid, SolanaAddresses.parse(input) != null, input) }
    }

    private val sendTx = """{"jsonrpc":"2.0","id":1,"result":{"blockTime":1717000000,"slot":268000000,
      "meta":{"err":null,"fee":5000,"preBalances":[2000000000,100000,2039280,2039280,1],"postBalances":[1499995000,500100000,2039280,2039280,1],
        "preTokenBalances":[
          {"accountIndex":2,"mint":"$usdc","owner":"$owner","programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA","uiTokenAmount":{"amount":"25000000","decimals":6,"uiAmount":25.0,"uiAmountString":"25"}},
          {"accountIndex":3,"mint":"$usdc","owner":"$other","programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA","uiTokenAmount":{"amount":"0","decimals":6,"uiAmount":null,"uiAmountString":"0"}}],
        "postTokenBalances":[
          {"accountIndex":2,"mint":"$usdc","owner":"$owner","programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA","uiTokenAmount":{"amount":"15000000","decimals":6,"uiAmount":15.0,"uiAmountString":"15"}},
          {"accountIndex":3,"mint":"$usdc","owner":"$other","programId":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA","uiTokenAmount":{"amount":"10000000","decimals":6,"uiAmount":10.0,"uiAmountString":"10"}}]},
      "transaction":{"signatures":["sigSend"],"message":{"accountKeys":[
        {"pubkey":"$owner","signer":true,"source":"transaction","writable":true},
        {"pubkey":"$other","signer":false,"source":"transaction","writable":true},
        {"pubkey":"AtaOwner1111111111111111111111111111111111","signer":false,"source":"transaction","writable":true},
        {"pubkey":"AtaOther1111111111111111111111111111111111","signer":false,"source":"transaction","writable":true},
        {"pubkey":"11111111111111111111111111111111","signer":false,"source":"transaction","writable":false}]}},"version":0}}"""

    private val receiveTx = """{"jsonrpc":"2.0","id":1,"result":{"blockTime":1716000000,"slot":267000000,
      "meta":{"err":null,"fee":5000,"preBalances":[3000005000,0,1],"postBalances":[1000000000,2000000000,1],"preTokenBalances":[],"postTokenBalances":[]},
      "transaction":{"signatures":["sigReceive"],"message":{"accountKeys":[
        {"pubkey":"$other","signer":true,"source":"transaction","writable":true},
        {"pubkey":"$owner","signer":false,"source":"transaction","writable":true},
        {"pubkey":"11111111111111111111111111111111","signer":false,"source":"transaction","writable":false}]}}}}"""

    private val engine = MockEngine { request ->
        val body = request.body.toByteArray().decodeToString()
        val response = when {
            "getBalance" in body -> {
                """{"jsonrpc":"2.0","result":{"context":{"slot":1},"value":1499995000},"id":1}"""
            }

            // One native stake account (2.5 SOL) withdrawable by the owner.
            "getProgramAccounts" in body -> {
                """{"jsonrpc":"2.0","result":[{"pubkey":"StakeAcc1111111111111111111111111111111111","account":{"lamports":2500000000,"data":["","base64"],"owner":"Stake11111111111111111111111111111111111111","executable":false,"rentEpoch":0}}],"id":1}"""
            }

            "getTokenAccountsByOwner" in body && "TokenzQd" in body -> {
                """{"jsonrpc":"2.0","result":{"context":{"slot":1},"value":[]},"id":1}"""
            }

            "getTokenAccountsByOwner" in body -> {
                """{"jsonrpc":"2.0","result":{"context":{"slot":1},"value":[
                {"pubkey":"AtaOwner1111111111111111111111111111111111","account":{"data":{"parsed":{"info":{"isNative":false,"mint":"$usdc","owner":"$owner","state":"initialized",
                 "tokenAmount":{"amount":"15000000","decimals":6,"uiAmount":15.0,"uiAmountString":"15"}},"type":"account"},"program":"spl-token","space":165},
                 "executable":false,"lamports":2039280,"owner":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA","rentEpoch":0}},
                {"pubkey":"Dust1111111111111111111111111111111111111111","account":{"data":{"parsed":{"info":{"mint":"Spam1111111111111111111111111111111111111111","owner":"$owner",
                 "tokenAmount":{"amount":"0","decimals":0,"uiAmount":0.0,"uiAmountString":"0"}},"type":"account"},"program":"spl-token","space":165}}}]},"id":1}"""
            }

            "getSignaturesForAddress" in body -> {
                """{"jsonrpc":"2.0","result":[
                {"signature":"sigSend","slot":268000000,"err":null,"memo":null,"blockTime":1717000000,"confirmationStatus":"finalized"},
                {"signature":"sigReceive","slot":267000000,"err":null,"memo":null,"blockTime":1716000000,"confirmationStatus":"finalized"},
                {"signature":"sigOld","slot":1,"err":null,"memo":null,"blockTime":1,"confirmationStatus":"finalized"}],"id":1}"""
            }

            "sigSend" in body -> {
                sendTx
            }

            "sigReceive" in body -> {
                receiveTx
            }

            else -> {
                error("Unexpected request $body")
            }
        }
        respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val adapter = SolanaAdapter(createHttpClient(engine = engine), "https://sol.test")
    private val target = assertNotNull(SolanaAddresses.parse(owner))

    @Test
    fun syncReturnsBalancesAndDeltas() = runTest {
        withContext(Dispatchers.Default) {
            val result = adapter.sync(target, knownTransactions = setOf("sigOld"))
            assertEquals(listOf("SOL", "SOL", "USDC"), result.balances.map { it.asset.symbol }, "zero spam balance skipped")
            assertEquals("1.499995", result.balances[0].amount.toPlainString())
            assertEquals("2.5", result.balances[1].amount.toPlainString(), "staked SOL")
            assertEquals("solana", result.balances[1].asset.priceId)
            assertEquals("15", result.balances[2].amount.toPlainString())
            assertEquals("usd-coin", result.balances[2].asset.priceId)

            val send = result.transactions.filter { it.hash == "sigSend" }.associateBy { it.asset.symbol }
            assertEquals(-500_005_000L, send.getValue("SOL").delta.longValue(), "0.5 SOL + 5000 lamports fee")
            assertEquals(5000L, send.getValue("SOL").fee?.longValue())
            assertEquals(-10_000_000L, send.getValue("USDC").delta.longValue())
            val receive = result.transactions.single { it.hash == "sigReceive" }
            assertEquals(2_000_000_000L, receive.delta.longValue())
            assertEquals(null, receive.fee)
            assertTrue(result.transactions.none { it.hash == "sigOld" })
        }
    }

    @Test
    fun fundingOwnStakeAccountIsASelfTransfer() {
        val stakeTx = com.altaran.watchwallet.core.network.NetworkJson.parseToJsonElement(
            """{"blockTime":1718000000,
          "meta":{"err":null,"fee":5000,"preBalances":[3000000000,0,1],"postBalances":[999995000,2000000000,1],"preTokenBalances":[],"postTokenBalances":[],"innerInstructions":[]},
          "transaction":{"message":{"accountKeys":[
            {"pubkey":"$owner","signer":true},{"pubkey":"StakeAcc1111111111111111111111111111111111","signer":true},{"pubkey":"Stake11111111111111111111111111111111111111","signer":false}],
            "instructions":[
              {"program":"system","programId":"11111111111111111111111111111111","parsed":{"type":"createAccount","info":{"source":"$owner","newAccount":"StakeAcc1111111111111111111111111111111111","lamports":2000000000,"owner":"Stake11111111111111111111111111111111111111"}}},
              {"program":"stake","programId":"Stake11111111111111111111111111111111111111","parsed":{"type":"initialize","info":{"stakeAccount":"StakeAcc1111111111111111111111111111111111"}}}]}}}""",
        )
        val sol = parseTransaction("sigStake", stakeTx, owner, emptyMap(), confirmed = true).single()
        assertTrue(sol.isSelfTransfer, "moving SOL into an own stake account is not a withdrawal")
        val transferTx = com.altaran.watchwallet.core.network.NetworkJson.parseToJsonElement(
            """{"meta":{"fee":5000,"preBalances":[1],"postBalances":[1]},"transaction":{"message":{"accountKeys":[],"instructions":[
              {"program":"system","parsed":{"type":"transfer","info":{"source":"$owner","destination":"$other","lamports":1}}}]}}}""",
        )
        assertTrue(!movesOwnStake(transferTx, owner))
    }

    @Test
    fun probeDetectsActivity() = runTest {
        withContext(Dispatchers.Default) {
            val probe = adapter.probe(WatchTarget.Address(target.family, target.chainId, owner))
            assertTrue(probe.hasActivity)
        }
    }

    /** 60 old signatures whose transactions change nothing of the wallet: they leave no row. */
    @Test
    fun signaturesWithoutChangesDoNotStallTheBackfill() = runBlocking {
        val noChange = """{"jsonrpc":"2.0","id":1,"result":{"blockTime":1,"slot":1,"meta":{"err":null,"fee":5000,"preBalances":[1,1],"postBalances":[1,1],"preTokenBalances":[],"postTokenBalances":[]},
            "transaction":{"message":{"accountKeys":[{"pubkey":"$other","signer":true},{"pubkey":"$owner","signer":false}]}}}}"""
        val all = (1..60).map { "sig$it" }
        val fetched = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            val response = when {
                "getBalance" in body -> {
                    """{"jsonrpc":"2.0","result":{"value":1},"id":1}"""
                }

                "getProgramAccounts" in body -> {
                    """{"jsonrpc":"2.0","result":[],"id":1}"""
                }

                "getTokenAccountsByOwner" in body -> {
                    """{"jsonrpc":"2.0","result":{"value":[]},"id":1}"""
                }

                "getSignaturesForAddress" in body -> {
                    val before = Regex("\"before\":\"(sig\\d+)\"").find(body)?.groupValues?.get(1)
                    val rest = if (before == null) all else all.dropWhile { it != before }.drop(1)
                    rest.take(100).joinToString(",", """{"jsonrpc":"2.0","id":1,"result":[""", "]}") {
                        """{"signature":"$it","confirmationStatus":"finalized"}"""
                    }
                }

                else -> {
                    fetched += Regex("sig\\d+").find(body)!!.value
                    noChange
                }
            }
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val adapter = SolanaAdapter(createHttpClient(engine = engine), "https://sol.test")
        var state: String? = null

        // sig1 is stored, so the head walk stops at once and only the backfill reads anything.
        suspend fun sync() = adapter.sync(
            target,
            setOf("sig1"),
            SyncOptions(
                continueHistory = true,
                chainState = state?.let {
                    mapOf(SolanaChain.ID to it)
                }.orEmpty(),
            ),
        ) { part ->
            part.state?.let { state = it }
        }
        assertTrue(sync().historyTruncated)
        assertEquals((2..26).map { "sig$it" }, fetched)
        assertEquals("sig26", state)
        fetched.clear()
        assertTrue(sync().historyTruncated)
        assertEquals((27..51).map { "sig$it" }, fetched, "the same signatures are not read again")
        fetched.clear()
        assertFalse(sync().historyTruncated, "the end was reached")
        assertEquals((52..60).map { "sig$it" }, fetched)
    }

    @Test
    fun aPrunedTransactionKeepsTheHistoryMarkedAsCutOff() = runBlocking {
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            val response = when {
                "getBalance" in body -> """{"jsonrpc":"2.0","result":{"value":1},"id":1}"""
                "getProgramAccounts" in body -> """{"jsonrpc":"2.0","result":[],"id":1}"""
                "getTokenAccountsByOwner" in body -> """{"jsonrpc":"2.0","result":{"value":[]},"id":1}"""
                "getSignaturesForAddress" in body -> """{"jsonrpc":"2.0","id":1,"result":[{"signature":"known"},{"signature":"old","confirmationStatus":"finalized"}]}"""
                else -> """{"jsonrpc":"2.0","id":1,"result":null}"""
            }
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val adapter = SolanaAdapter(createHttpClient(engine = engine), "https://sol.test")
        val result = adapter.sync(target, setOf("known"), SyncOptions(continueHistory = true)) { }
        assertTrue(result.historyTruncated)
        assertTrue(result.storedProgressively)
    }
}
