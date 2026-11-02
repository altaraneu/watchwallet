package com.altaran.watchwallet.core.data.chain

import com.altaran.watchwallet.chain.algorand.AlgorandAdapter
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.aptos.AptosAdapter
import com.altaran.watchwallet.chain.bitcoin.BitcoinAdapter
import com.altaran.watchwallet.chain.bitcoincash.BitcoinCashAdapter
import com.altaran.watchwallet.chain.cosmos.CosmosAdapter
import com.altaran.watchwallet.chain.dogecoin.DogecoinAdapter
import com.altaran.watchwallet.chain.evm.EvmAdapter
import com.altaran.watchwallet.chain.evm.EvmNetworks
import com.altaran.watchwallet.chain.hedera.HederaAdapter
import com.altaran.watchwallet.chain.kaspa.KaspaAdapter
import com.altaran.watchwallet.chain.litecoin.LitecoinAdapter
import com.altaran.watchwallet.chain.stellar.StellarAdapter
import com.altaran.watchwallet.chain.tezos.TezosAdapter
import com.altaran.watchwallet.chain.ton.TonAdapter
import com.altaran.watchwallet.chain.tron.TronAdapter
import com.altaran.watchwallet.core.endpoints.EndpointCatalog
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup imported by v1.0.0 could store any text as an address, and it stays stored. Whatever it
 * says, every adapter that builds a URL from an address must keep asking the same path of the host.
 */
class AdapterRequestPathTest {
    private class Case(val name: String, val family: ChainFamily, val chain: String, val adapter: (HttpClient) -> ChainAdapter)

    private val cases = listOf(
        Case("bitcoin", ChainFamily.UTXO, "bitcoin") { BitcoinAdapter(it, listOf(BASE)) },
        Case("litecoin", ChainFamily.UTXO, "litecoin") { LitecoinAdapter(it, BASE) },
        Case("dogecoin", ChainFamily.UTXO, "dogecoin") { DogecoinAdapter(it, BASE) },
        Case("bitcoincash", ChainFamily.UTXO, "bitcoincash") { BitcoinCashAdapter(it, BASE) },
        Case("tron", ChainFamily.TRON, "tron") { TronAdapter(it, BASE) },
        Case("ton", ChainFamily.TON, "ton") { TonAdapter(it, BASE) },
        Case("cosmos", ChainFamily.COSMOS, "cosmos") { CosmosAdapter(it, BASE) },
        Case("stellar", ChainFamily.STELLAR, "stellar") { StellarAdapter(it, BASE) },
        Case("aptos", ChainFamily.APTOS, "aptos") { AptosAdapter(it, BASE) },
        Case("hedera", ChainFamily.HEDERA, "hedera") { HederaAdapter(it, BASE) },
        Case("algorand", ChainFamily.ALGORAND, "algorand") { AlgorandAdapter(it, BASE) },
        Case("tezos", ChainFamily.TEZOS, "tezos") { TezosAdapter(it, BASE) },
        Case("kaspa", ChainFamily.KASPA, "kaspa") { KaspaAdapter(it, BASE) },
        Case("evm", ChainFamily.EVM, "evm:1") { EvmAdapter(it, EvmNetworks.withEndpoints(EndpointCatalog.BUNDLED)) },
    )

    /** Every request an adapter makes for [value]; failures are fine, as it is not a real address. */
    private fun requests(case: Case, value: String): List<Url> = runBlocking {
        val seen = mutableListOf<Url>()
        val engine = MockEngine { request ->
            seen += request.url
            respondError(HttpStatusCode.NotFound)
        }
        val target = WatchTarget.Address(case.family, ChainId(case.chain), value)
        val adapter = case.adapter(createHttpClient(engine = engine))
        ignoringFailure { adapter.probe(target) }
        ignoringFailure { adapter.sync(target, emptySet()) }
        seen.toList()
    }

    private suspend fun ignoringFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Only the requests matter here.
        }
    }

    @Test
    fun aPathInTheAddressStaysInsideItsSegment() {
        cases.forEach { case ->
            requests(case, "../../admin").forEach { url ->
                assertFalse("${case.name}: $url", url.encodedPath.contains("../"))
                assertFalse("${case.name}: $url", url.encodedPath.contains("/admin"))
            }
        }
    }

    @Test
    fun aQueryInTheAddressStaysInsideItsSegment() {
        cases.forEach { case ->
            requests(case, "abc?injected=1&limit=999#frag").forEach { url ->
                assertEquals("${case.name}: $url", null, url.parameters["injected"])
                assertFalse("${case.name}: $url", url.parameters.getAll("limit").orEmpty().contains("999"))
                assertEquals("${case.name}: $url", "", url.fragment)
            }
        }
    }

    @Test
    fun anAlreadyEncodedSlashIsNotDecodedIntoOne() {
        cases.forEach { case ->
            requests(case, "a%2Fb").forEach { url ->
                assertFalse("${case.name}: $url", url.encodedPath.contains("a%2Fb"))
                assertFalse("${case.name}: $url", url.encodedPath.contains("a/b"))
            }
        }
    }

    @Test
    fun aDotSegmentOrNothingIsNeverRequested() {
        cases.forEach { case ->
            listOf("..", ".", "").forEach { value ->
                requests(case, value).forEach { url ->
                    assertFalse("${case.name} <$value>: $url", url.encodedPath.split('/').contains(".."))
                    assertFalse("${case.name} <$value>: $url", url.encodedPath.endsWith("/accounts/") || url.encodedPath.endsWith("/address/"))
                }
            }
        }
    }

    /** Guards the test itself, and that valid requests did not change. */
    @Test
    fun validAddressesAppearUnchangedInThePath() {
        val valid = mapOf(
            "bitcoin" to "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
            "litecoin" to "LVg2kJoFNg45Nbpy53h7Fe1wKyeXVRhMH9",
            "dogecoin" to "DH5yaieqoZN36fDVciNyRueRGvGLR3mr7L",
            "tron" to "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb",
            "cosmos" to "cosmos1fl48vsnmsdzcv85q5d2q4z5ajdha8yu34mf0eh",
            "stellar" to "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN",
            "aptos" to "0x1",
            "hedera" to "0.0.2",
            "algorand" to "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAY5HFKQ",
            "tezos" to "tz1burnburnburnburnburnburnburjAYjjX",
            "kaspa" to "kaspa:qqkqkzjvr7zwxxmjxjkmxxdwju9kjs6e9u82uh59z07vgaks6gg62v8707g73",
            "evm" to "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
        )
        cases.filter { it.name in valid }.forEach { case ->
            val address = valid.getValue(case.name)
            val urls = requests(case, address)
            assertTrue("${case.name} made no request with the address: $urls", urls.any { "/$address" in it.encodedPath })
        }
    }

    /** The cursor is appended to the origin, so without a slash `@host` would name another server. */
    @Test
    fun aHederaCursorCannotLeadToAnotherHost() = runBlocking {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            hosts += request.url.host
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                "/accounts/" in request.url.encodedPath -> respond("""{"balance":{"balance":1,"tokens":[]}}""", HttpStatusCode.OK, json)
                else -> respond("""{"transactions":[],"links":{"next":"@evil.test/steal"}}""", HttpStatusCode.OK, json)
            }
        }
        val adapter = HederaAdapter(createHttpClient(engine = engine), "https://node.test/api/v1")
        adapter.sync(WatchTarget.Address(ChainFamily.HEDERA, ChainId("hedera"), "0.0.2"), emptySet())
        assertEquals(setOf("node.test"), hosts.toSet())
    }

    private companion object {
        const val BASE = "https://node.test/api"
    }
}
