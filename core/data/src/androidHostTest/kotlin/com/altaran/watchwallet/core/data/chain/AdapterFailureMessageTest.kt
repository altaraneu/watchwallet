package com.altaran.watchwallet.core.data.chain

import com.altaran.watchwallet.chain.algorand.AlgorandAdapter
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.aptos.AptosAdapter
import com.altaran.watchwallet.chain.bitcoin.BitcoinAdapter
import com.altaran.watchwallet.chain.bitcoincash.BitcoinCashAdapter
import com.altaran.watchwallet.chain.cardano.CardanoAdapter
import com.altaran.watchwallet.chain.cosmos.CosmosAdapter
import com.altaran.watchwallet.chain.dogecoin.DogecoinAdapter
import com.altaran.watchwallet.chain.evm.EvmAdapter
import com.altaran.watchwallet.chain.evm.EvmNetworks
import com.altaran.watchwallet.chain.hedera.HederaAdapter
import com.altaran.watchwallet.chain.kaspa.KaspaAdapter
import com.altaran.watchwallet.chain.litecoin.LitecoinAdapter
import com.altaran.watchwallet.chain.near.NearAdapter
import com.altaran.watchwallet.chain.polkadot.PolkadotAdapter
import com.altaran.watchwallet.chain.polkadot.PolkadotChains
import com.altaran.watchwallet.chain.solana.SolanaAdapter
import com.altaran.watchwallet.chain.stellar.StellarAdapter
import com.altaran.watchwallet.chain.sui.SuiAdapter
import com.altaran.watchwallet.chain.tezos.TezosAdapter
import com.altaran.watchwallet.chain.ton.TonAdapter
import com.altaran.watchwallet.chain.tron.TronAdapter
import com.altaran.watchwallet.chain.xrp.XrpAdapter
import com.altaran.watchwallet.core.endpoints.EndpointCatalog
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.createHttpClient
import com.altaran.watchwallet.core.network.safeDescription
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whatever goes wrong, an exception leaving an adapter must not hold the address it was about: an
 * HTTP client's message holds the URL, a parser's or a server's error text can quote it. That covers
 * the message, `toString` and every cause, as any of them can end up in a state, log or crash report.
 */
class AdapterFailureMessageTest {
    private class Case(val name: String, val family: ChainFamily, val chain: String, val address: String, val adapter: (HttpClient) -> ChainAdapter)

    private val cases = listOf(
        Case("bitcoin", ChainFamily.UTXO, "bitcoin", "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq") { BitcoinAdapter(it, listOf(BASE)) },
        Case("litecoin", ChainFamily.UTXO, "litecoin", "ltc1qw508d6qejxtdg4y5r3zarvary0c5xw7kgmn4n9") { LitecoinAdapter(it, BASE) },
        Case("dogecoin", ChainFamily.UTXO, "dogecoin", "DH5yaieqoZN36fDVciNyRueRGvGLR3mr7L") { DogecoinAdapter(it, BASE) },
        Case("bitcoincash", ChainFamily.UTXO, "bitcoincash", "qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a") { BitcoinCashAdapter(it, BASE) },
        Case("evm", ChainFamily.EVM, "evm:1", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed") {
            EvmAdapter(it, EvmNetworks.withEndpoints(EndpointCatalog.BUNDLED))
        },
        Case("solana", ChainFamily.SOLANA, "solana", "So11111111111111111111111111111111111111112") { SolanaAdapter(it, listOf(BASE)) },
        Case("xrp", ChainFamily.XRP, "xrp", "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh") { XrpAdapter(it, listOf(BASE)) },
        Case("tron", ChainFamily.TRON, "tron", "TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7") { TronAdapter(it, BASE) },
        Case("ton", ChainFamily.TON, "ton", "EQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqB2N") { TonAdapter(it, BASE) },
        Case("cardano", ChainFamily.CARDANO, "cardano", "stake1uyehkck0lajq8gr28t9uxnuvgcqrc6070x3k9r8048z8y5gh6ffgw") { CardanoAdapter(it, BASE) },
        Case("cosmos", ChainFamily.COSMOS, "cosmos", "cosmos1hsk6jryyqjfhp5dhc55tc9jtckygx0eph6dd02") { CosmosAdapter(it, BASE) },
        Case("polkadot", ChainFamily.POLKADOT, "polkadot", "15oF4uVJwmo4TdGW7VfQxNLavjCXviqxT9S1MgbjMNHr6Sp5") {
            PolkadotAdapter(it, PolkadotChains.withEndpoints(EndpointCatalog.BUNDLED))
        },
        Case("stellar", ChainFamily.STELLAR, "stellar", "GAAZI4TCR3TO5OJHCTJC2A4QSY6CJWJH5IAJTGKIN2ER7LBNVKOCCWN7") { StellarAdapter(it, BASE) },
        Case("sui", ChainFamily.SUI, "sui", "0x0000000000000000000000000000000000000000000000000000000000000abc") { SuiAdapter(it, BASE) },
        Case("aptos", ChainFamily.APTOS, "aptos", "0x0000000000000000000000000000000000000000000000000000000000000abd") { AptosAdapter(it, BASE) },
        Case("near", ChainFamily.NEAR, "near", "someone.near") { NearAdapter(it, listOf(BASE)) },
        Case("hedera", ChainFamily.HEDERA, "hedera", "0.0.1234567") { HederaAdapter(it, BASE) },
        Case("algorand", ChainFamily.ALGORAND, "algorand", "VCMJKWOY5P5P7SKMZFFOCEROPJCZOTIJMNIYNUCKH7LRO45JMJP6UYBIJA") { AlgorandAdapter(it, BASE) },
        Case("tezos", ChainFamily.TEZOS, "tezos", "tz1VSUr8wwNhLAzempoch5d6hLRiTh8Cjcjb") { TezosAdapter(it, BASE) },
        Case("kaspa", ChainFamily.KASPA, "kaspa", "kaspa:qqkqkzjvr7zwxxmjxjkmxxdwju9kjs6e9u82uh59z07vgaks6gg62v8707g73") { KaspaAdapter(it, BASE) },
    )

    private class Answer(val name: String, val status: HttpStatusCode, val body: (String) -> String)

    private val answers = listOf(
        Answer("HTTP error quoting it", HttpStatusCode.BadRequest) { """{"message":"unknown account $it"}""" },
        Answer("not found", HttpStatusCode.NotFound) { "no such account $it" },
        Answer("JSON-RPC error, Sui errors and an inline error at once", HttpStatusCode.OK) {
            """{"error":{"code":-32000,"message":"account $it"},"errors":[{"message":"account $it"}],"result":{"error":"account $it"}}"""
        },
        Answer("an answer no parser can read", HttpStatusCode.OK) { "<html>account $it</html>" },
        Answer("JSON of the wrong shape", HttpStatusCode.OK) { """{"address":"$it","balance":{"nested":"$it"}}""" },
    )

    private fun Throwable.chain() = generateSequence(this) { it.cause }

    private suspend fun assertClean(what: String, address: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            failure.chain().forEach { step ->
                assertFalse("$what: ${step::class.simpleName}", step.message.orEmpty().contains(address, ignoreCase = true))
                assertFalse("$what: toString of ${step::class.simpleName}", step.toString().contains(address, ignoreCase = true))
            }
        }
    }

    @Test
    fun noExceptionOfAnyAdapterHoldsTheAddress() = runBlocking {
        cases.forEach { case ->
            answers.forEach { answer ->
                val engine = MockEngine {
                    respond(answer.body(case.address), answer.status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                val target = WatchTarget.Address(case.family, ChainId(case.chain), case.address)
                val adapter = case.adapter(createHttpClient(engine = engine))
                assertClean("${case.name} probe, ${answer.name}", case.address) { adapter.probe(target) }
                assertClean("${case.name} sync, ${answer.name}", case.address) { adapter.sync(target, emptySet()) }
            }
        }
    }

    /** The guard above only means something if the raw exception really holds the address. */
    @Test
    fun theHttpClientsOwnMessageDoesHoldTheUrlSoOnlyTypeAndStatusAreKept() = runBlocking {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val client = createHttpClient(engine = MockEngine { respond("nope", HttpStatusCode.NotFound) })
        val failure = try {
            client.get("$BASE/address/$address")
            error("expected a failure")
        } catch (e: ResponseException) {
            e
        }
        assertTrue(failure.message.orEmpty().contains(address))
        assertEquals("ClientRequestException HTTP 404", failure.safeDescription())
    }

    private companion object {
        const val BASE = "https://chain.test"
    }
}
