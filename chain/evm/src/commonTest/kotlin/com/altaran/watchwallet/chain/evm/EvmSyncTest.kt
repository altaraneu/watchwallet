// Fixtures contain long JSON payloads.
@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength")

package com.altaran.watchwallet.chain.evm

import com.altaran.watchwallet.chain.api.SyncOptions
import com.altaran.watchwallet.chain.api.SyncPart
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** A wallet active on some mocked networks; failures and pauses are switched per test. */
private class FakeChains {
    val address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    val own = address.lowercase()
    val target = WatchTarget.Address(ChainFamily.EVM, ChainId.evm(1), address)
    val now = Instant.fromEpochSeconds(2_000_000_000)

    val requests = mutableListOf<String>()
    private val lock = Mutex()

    /** Network → native balance and nonce (hex); absent = zero. */
    val onChain = HashMap<Long, Pair<String, String>>()
    val counters = HashMap<String, String>()

    /** Native transaction pages per Blockscout host; the first one is the newest. */
    val nativePages = HashMap<String, List<List<String>>>()
    val tokenBalances = HashMap<String, String>()
    var respondOverride: (suspend MockRequestHandleScope.(host: String, url: String) -> HttpResponseData?)? = null

    fun network(id: Long, indexed: Boolean = true, fallback: Boolean = false) = EvmNetwork(
        id,
        "Net$id",
        "ETH",
        "Ether",
        "ethereum",
        "https://rpc$id.test/",
        if (indexed) "https://bs$id.test" else null,
        "https://x.test",
        null,
        0,
        rpcFallbacks = if (fallback) listOf("https://alt$id.test/") else emptyList(),
    )

    fun tx(hash: String) = """{"hash":"$hash","timestamp":"2024-01-01T00:00:00.000000Z","from":{"hash":"0xother"},"to":{"hash":"$own"},"value":"5","fee":{"value":"1"},"status":"ok","result":"success"}"""

    private fun MockRequestHandleScope.json(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    val engine = MockEngine { request ->
        val host = request.url.host
        val url = request.url.toString()
        lock.withLock { requests += url }
        respondOverride?.invoke(this, host, url)?.let { return@MockEngine it }
        when {
            host.startsWith("rpc") || host.startsWith("alt") -> {
                val id = host.removePrefix("rpc").removePrefix("alt").removeSuffix(".test").toLong()
                val (balance, nonce) = onChain[id] ?: ("0x0" to "0x0")
                val body = request.body.toByteArray().decodeToString()
                json("""{"result":"${if ("eth_getBalance" in body) balance else nonce}"}""")
            }

            url.endsWith("/counters") -> {
                json(counters[host] ?: """{"transactions_count":"0","token_transfers_count":"0"}""")
            }

            url.contains("/token-balances") -> {
                json(tokenBalances[host] ?: "[]")
            }

            url.contains("/internal-transactions") || url.contains("/token-transfers") -> {
                json("""{"items":[],"next_page_params":null}""")
            }

            url.contains("/transactions") -> {
                val pages = nativePages[host].orEmpty()
                val page = request.url.parameters["page"]?.toInt() ?: 0
                val next = if (page + 1 < pages.size) """{"page":${page + 1},"items_count":50}""" else "null"
                json("""{"items":[${pages.getOrElse(page) { emptyList() }.joinToString(",")}],"next_page_params":$next}""")
            }

            else -> {
                error("Unexpected $url")
            }
        }
    }

    fun adapter(vararg networks: EvmNetwork, deadlineMs: Long = 2_000) =
        EvmAdapter(createHttpClient(engine = engine), networks.toList(), { now }, deadlineMs.milliseconds)

    fun hostsContacted(): Set<String> = requests.map { it.substringAfter("://").substringBefore('/') }.toSet()

    fun listRequests(): Int = requests.count { "/transactions" in it || "/internal-transactions" in it || "/token-transfers" in it }

    /** Runs one sync the way the engine does and returns what reached the sink. */
    suspend fun sync(adapter: EvmAdapter, known: Set<String> = emptySet(), options: SyncOptions): Pair<SyncResult, List<SyncPart>> {
        val parts = mutableListOf<SyncPart>()
        val partsLock = Mutex()
        val result = adapter.sync(target, known, options) { part -> partsLock.withLock { parts += part } }
        return result to parts
    }
}

class EvmSyncTest {
    private fun scenario(block: suspend FakeChains.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { FakeChains().block() }
    }

    private fun FakeChains.recently() = SyncOptions(activeChains = setOf(ChainId.evm(1)), lastDiscoveryAt = now - 1.hours)

    private fun List<SyncPart>.on(id: Long) = filter { it.chainId == ChainId.evm(id) }

    // ------------------------------------------------------------ A: discovery

    @Test
    fun whichNetworksARefreshTouches() = scenario {
        val one = network(1)
        val two = network(2)
        class Case(val name: String, val options: SyncOptions, val expected: Set<String>)
        val all = setOf("rpc1.test", "bs1.test", "rpc2.test", "bs2.test")
        listOf(
            Case("routine: active only", recently(), setOf("rpc1.test", "bs1.test")),
            Case("first sync: everything", SyncOptions(), all),
            Case("stale discovery: everything", SyncOptions(setOf(ChainId.evm(1)), now - 25.hours), all),
            Case("manual: everything", recently().copy(manual = true), all),
            Case("no active chains, discovered recently: nothing", SyncOptions(lastDiscoveryAt = now - 1.hours), emptySet()),
        ).forEach { case ->
            onChain[1] = "0x1" to "0x1"
            requests.clear()
            val (result, _) = sync(adapter(one, two), options = case.options)
            assertEquals(case.expected, hostsContacted(), case.name)
            assertEquals(case.expected.size == 4, result.discoveryCompleted, case.name)
        }
    }

    @Test
    fun laterActivityIsAddedWithoutTouchingOtherNetworks() = scenario {
        onChain[1] = "0x1" to "0x1"
        onChain[2] = "0x2" to "0x0"
        val (_, parts) = sync(adapter(network(1), network(2), network(3)), options = SyncOptions(setOf(ChainId.evm(1)), now - 25.hours))
        assertEquals(true, parts.on(2).first().active, "new network added")
        assertEquals(true, parts.on(1).first().active, "old one stays")
        assertTrue(parts.on(3).isEmpty(), "inactive networks cause no writes")
    }

    @Test
    fun receiveOnlyActivityIsFoundThroughTheIndexer() = scenario {
        counters["bs2.test"] = """{"transactions_count":"0","token_transfers_count":"3"}"""
        val (_, parts) = sync(adapter(network(2)), options = SyncOptions())
        assertEquals(true, parts.single { it.active != null }.active)
    }

    @Test
    fun networkWithoutActivityAnyMoreIsCleared() = scenario {
        val (_, parts) = sync(adapter(network(1)), options = SyncOptions(setOf(ChainId.evm(1)), now - 1.hours))
        val part = parts.single()
        assertEquals(false, part.active)
        assertTrue(part.balancesComplete)
    }

    @Test
    fun manyUnreachableNetworksLeaveDiscoveryDue() = scenario {
        onChain[1] = "0x1" to "0x1"
        respondOverride = { host, _ -> if (host == "rpc2.test" || host == "rpc3.test") respond("x", HttpStatusCode.BadGateway) else null }
        val (result, _) = sync(adapter(network(1), network(2), network(3)), options = SyncOptions())
        assertFalse(result.discoveryCompleted)
        assertFalse(result.allChainsFailed)
    }

    // ------------------------------------------------------------ B: progressive storing

    @Test
    fun slowNetworkDoesNotDelayOthers() = scenario {
        onChain[1] = "0x1" to "0x1"
        onChain[2] = "0x2" to "0x1"
        val fastStored = CompletableDeferred<Unit>()
        val slowFinished = CompletableDeferred<Unit>()
        respondOverride = { host, _ ->
            if (host == "rpc2.test") fastStored.await()
            null
        }
        val order = mutableListOf<Long>()
        adapter(network(1, indexed = false), network(2, indexed = false)).sync(target, emptySet(), SyncOptions()) { part ->
            order += part.chainId.value.removePrefix("evm:").toLong()
            if (part.chainId == ChainId.evm(1)) fastStored.complete(Unit) else slowFinished.complete(Unit)
        }
        slowFinished.await()
        assertEquals(listOf(1L, 2L), order)
    }

    @Test
    fun failingNetworkKeepsStoredDataAndOthersAreStored() = scenario {
        onChain[1] = "0x1" to "0x1"
        val failing = setOf(ChainId.evm(2))
        respondOverride = { host, _ -> if (host == "rpc2.test") respond("x", HttpStatusCode.Forbidden) else null }
        val (result, parts) = sync(adapter(network(1), network(2)), options = SyncOptions(setOf(ChainId.evm(1), ChainId.evm(2)), now - 1.hours))
        assertTrue(parts.on(2).isEmpty(), "no part means nothing stored of that network changes")
        assertEquals(1, parts.on(1).count { it.balancesComplete })
        assertEquals(failing, result.failedChains.toSet())
        assertFalse(result.allChainsFailed)
    }

    @Test
    fun allNetworksUnreachableFailsTheSync() = scenario {
        respondOverride = { _, _ -> respond("x", HttpStatusCode.Forbidden) }
        assertFailsWith<com.altaran.watchwallet.chain.api.ChainDataException> { sync(adapter(network(1)), options = recently()) }
    }

    @Test
    fun blockedIndexerStillUpdatesTheNativeBalanceAndKeepsTokens() = scenario {
        onChain[1] = "0x1" to "0x1"
        respondOverride = { host, _ -> if (host == "bs1.test") respond("challenge", HttpStatusCode.Forbidden) else null }
        val (result, parts) = sync(adapter(network(1)), options = recently())
        val part = parts.single()
        assertEquals(listOf("ETH"), part.balances.map { it.asset.symbol })
        assertFalse(part.balancesComplete, "stored tokens are kept")
        assertFalse(part.historyFetched)
        assertTrue(result.allChainsFailed, "the only active network is not fully refreshed")
    }

    @Test
    fun balancesAreStoredBeforeHistoryIsFetched() = scenario {
        onChain[1] = "0x1" to "0x1"
        nativePages["bs1.test"] = listOf(listOf(tx("0xa")))
        val balancesStored = CompletableDeferred<Unit>()
        respondOverride = { _, url ->
            if ("/transactions" in url) balancesStored.await()
            null
        }
        adapter(network(1)).sync(target, emptySet(), SyncOptions()) { part ->
            if (part.balancesComplete) balancesStored.complete(Unit)
        }
        assertTrue(balancesStored.isCompleted)
    }

    @Test
    fun failedHistoryKeepsBalancesAndStoresNoRows() = scenario {
        onChain[1] = "0x1" to "0x1"
        nativePages["bs1.test"] = listOf(listOf(tx("0xnew"), tx("0xold")))
        respondOverride = { _, url -> if ("/internal-transactions" in url) respond("x", HttpStatusCode.NotFound) else null }
        val (result, parts) = sync(adapter(network(1)), setOf("0xold"), recently())
        assertTrue(parts.single().balancesComplete, "balances stored")
        assertTrue(parts.none { it.transactions.isNotEmpty() }, "a list failing with known transactions stores nothing: no gap")
        assertEquals(listOf(ChainId.evm(1)), result.failedChains)
    }

    // ------------------------------------------------------------ C: deadlines and failures

    @Test
    fun hangingNetworkHitsItsDeadlineAndFailsAlone() = scenario {
        onChain[1] = "0x1" to "0x1"
        respondOverride = { host, _ ->
            if (host == "rpc2.test") awaitCancellation()
            null
        }
        val (result, parts) = sync(adapter(network(1, indexed = false), network(2, indexed = false), deadlineMs = 200), options = SyncOptions())
        assertTrue(parts.on(2).isEmpty())
        assertEquals(1, parts.on(1).size)
        assertTrue(result.failedChains.isEmpty(), "an inactive network that is unreachable is no failure")
    }

    @Test
    fun forbiddenIndexerIsNotAskedAgainThisSession() = scenario {
        onChain[1] = "0x1" to "0x1"
        respondOverride = { host, _ -> if (host == "bs1.test") respond("x", HttpStatusCode.Forbidden) else null }
        val adapter = adapter(network(1))
        sync(adapter, options = recently())
        val first = requests.count { "bs1.test" in it }
        assertEquals(1, first, "no retry of a 403, and no further requests after it")
        sync(adapter, options = recently())
        assertEquals(first, requests.count { "bs1.test" in it })
        sync(adapter, options = recently().copy(manual = true))
        assertTrue(requests.count { "bs1.test" in it } > first, "a manual refresh tries again")
    }

    // ------------------------------------------------------------ D: cheap refresh

    private fun FakeChains.cheapSetup() {
        onChain[1] = "0x1" to "0x1"
        counters["bs1.test"] = """{"transactions_count":"2","token_transfers_count":"0"}"""
        nativePages["bs1.test"] = listOf(listOf(tx("0xa"), tx("0xb")))
    }

    private suspend fun FakeChains.settled(adapter: EvmAdapter): SyncOptions {
        val state = sync(adapter, options = recently()).second.mapNotNull { it.state }.single()
        return recently().copy(chainState = mapOf(ChainId.evm(1) to state))
    }

    @Test
    fun whenTheHistoryIsWalked() = scenario {
        class Case(val name: String, val walked: Boolean, val change: FakeChains.() -> Unit = {}, val options: (SyncOptions) -> SyncOptions = { it })
        listOf(
            Case("nothing changed", walked = false),
            Case("nonce changed", walked = true, change = { onChain[1] = "0x1" to "0x2" }),
            Case("native balance changed (incoming internal transfer)", walked = true, change = { onChain[1] = "0x2" to "0x1" }),
            Case("transaction counter changed", walked = true, change = {
                counters["bs1.test"] = """{"transactions_count":"3","token_transfers_count":"0"}"""
            }),
            Case("token transfer counter changed", walked = true, change = {
                counters["bs1.test"] = """{"transactions_count":"2","token_transfers_count":"1"}"""
            }),
            Case("token balance changed", walked = true, change = {
                tokenBalances["bs1.test"] = """[{"token":{"address_hash":"0x11","symbol":"T","name":"T","decimals":"1","type":"ERC-20"},"value":"9"}]"""
            }),
            Case("counters unavailable", walked = true, change = {
                respondOverride = { _, url -> if (url.endsWith("/counters")) respond("x", HttpStatusCode.NotFound) else null }
            }),
            Case("manual refresh", walked = true, options = { it.copy(manual = true) }),
            Case("discovery due", walked = true, options = { it.copy(lastDiscoveryAt = now - 25.hours) }),
        ).forEach { case ->
            val chains = FakeChains()
            chains.cheapSetup()
            val adapter = chains.adapter(chains.network(1))
            val options = chains.settled(adapter)
            chains.requests.clear()
            case.change(chains)
            val (_, parts) = chains.sync(adapter, setOf("0xa", "0xb"), case.options(options))
            assertEquals(case.walked, chains.listRequests() > 0, case.name)
            assertEquals(case.walked, parts.any { it.historyFetched }, case.name)
            assertTrue(chains.requests.any { "/token-balances" in it }, "token balances are always fetched: ${case.name}")
        }
    }

    @Test
    fun pendingTransactionsAreAlwaysLookedAtAgain() = scenario {
        cheapSetup()
        val pending = """{"hash":"0xp","from":{"hash":"0xo"},"to":{"hash":"$own"},"value":"5","status":null}"""
        nativePages["bs1.test"] = listOf(listOf(pending, tx("0xa")))
        val adapter = adapter(network(1))
        val options = settled(adapter)
        requests.clear()
        val (_, parts) = sync(adapter, setOf("0xa"), options)
        assertTrue(listRequests() > 0)
        assertTrue(parts.any { it.historyFetched })
    }

    @Test
    fun failedHistoryDoesNotMarkTheNetworkAsUnchanged() = scenario {
        cheapSetup()
        val adapter = adapter(network(1))
        val options = settled(adapter)
        // A new transaction arrives, but the history cannot be read this time.
        onChain[1] = "0x1" to "0x2"
        nativePages["bs1.test"] = listOf(listOf(tx("0xc"), tx("0xa"), tx("0xb")))
        respondOverride = { _, url -> if ("/transactions" in url) respond("x", HttpStatusCode.NotFound) else null }
        val (_, failed) = sync(adapter, setOf("0xa", "0xb"), options)
        assertTrue(failed.none { it.state != null }, "the old state stays")
        // The next sync still sees the difference, and finds the transaction.
        respondOverride = null
        val (_, parts) = sync(adapter, setOf("0xa", "0xb"), options)
        assertEquals(listOf("0xc", "0xa", "0xb"), parts.flatMap { it.transactions }.map { it.hash })
    }

    @Test
    fun unreadableStateStartsOver() = scenario {
        cheapSetup()
        val adapter = adapter(network(1))
        val (_, parts) = sync(adapter, setOf("0xa"), recently().copy(chainState = mapOf(ChainId.evm(1) to "{broken")))
        assertTrue(parts.any { it.historyFetched })
    }

    @Test
    fun rememberedStateIsIgnoredWhenNoTransactionIsStored() = scenario {
        cheapSetup()
        val adapter = adapter(network(1))
        val options = settled(adapter)
        requests.clear()
        sync(adapter, emptySet(), options)
        assertTrue(listRequests() > 0, "cached rows were dropped: fetch them again")
    }

    // ------------------------------------------------------------ E: failover

    @Test
    fun rpcFallsOverAndRemembersTheFailingHost() = scenario {
        onChain[1] = "0x1" to "0x1"
        respondOverride = { host, _ -> if (host == "rpc1.test") respond("x", HttpStatusCode.BadGateway) else null }
        val adapter = adapter(network(1, indexed = false, fallback = true))
        val (_, parts) = sync(adapter, options = recently())
        assertEquals(listOf("ETH"), parts.single().balances.map { it.asset.symbol })
        assertTrue("alt1.test" in hostsContacted())
        requests.clear()
        sync(adapter, options = recently())
        assertEquals(setOf("alt1.test"), hostsContacted(), "the failing host is not waited for again")
    }

    @Test
    fun rpcErrorAnswerTriesTheNextHost() = scenario {
        onChain[1] = "0x1" to "0x1"
        respondOverride = { host, _ ->
            if (host == "rpc1.test") {
                respond("""{"error":{"code":-32005,"message":"rate limit"}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                null
            }
        }
        val (_, parts) = sync(adapter(network(1, indexed = false, fallback = true)), options = recently())
        assertEquals(1, parts.size)
    }

    // ------------------------------------------------------------ G: continuation

    private fun FakeChains.pages(count: Int) = List(count) { page -> listOf(tx("0x$page")) }

    @Test
    fun longHistoryIsFinishedAcrossSyncsWithoutGaps() = scenario {
        onChain[1] = "0x1" to "0x1"
        nativePages["bs1.test"] = pages(45)
        val adapter = adapter(network(1))
        val all = (0 until 45).map { "0x$it" }

        val (first, firstParts) = sync(adapter, options = recently())
        val seen = firstParts.flatMap { it.transactions }.map { it.hash }.toMutableList()
        assertEquals(20, seen.size, "head walk stops at its page limit")
        assertTrue(first.historyTruncated)
        var state = assertNotNull(firstParts.mapNotNull { it.state }.single())

        var syncs = 0
        var truncated = true
        while (truncated) {
            val (result, parts) = sync(adapter, seen.toSet(), recently().copy(continueHistory = true, chainState = mapOf(ChainId.evm(1) to state)))
            seen += parts.flatMap { it.transactions }.map { it.hash }
            state = parts.mapNotNull { it.state }.singleOrNull() ?: state
            truncated = result.historyTruncated
            assertTrue(++syncs < 10, "must finish")
        }
        assertEquals(all.toSet(), seen.toSet(), "no gap")
        assertEquals(3, syncs, "25 pages left at 10 per sync")
    }

    @Test
    fun newTransactionsAreFoundWhileBackfilling() = scenario {
        onChain[1] = "0x1" to "0x1"
        nativePages["bs1.test"] = pages(25)
        val adapter = adapter(network(1))
        val (_, firstParts) = sync(adapter, options = recently())
        val seen = firstParts.flatMap { it.transactions }.map { it.hash }.toSet()
        val state = firstParts.mapNotNull { it.state }.single()

        // New on the top. Page numbers shift, like any list changing while it is being paged.
        onChain[1] = "0x1" to "0x2"
        nativePages["bs1.test"] = listOf(listOf(tx("0xnew"))) + pages(25)
        val (_, parts) = sync(adapter, seen, recently().copy(continueHistory = true, chainState = mapOf(ChainId.evm(1) to state)))
        assertTrue("0xnew" in parts.flatMap { it.transactions }.map { it.hash })
    }

    @Test
    fun walletMarkedTruncatedByAnOlderVersionWalksFromTheTop() = scenario {
        onChain[1] = "0x1" to "0x1"
        nativePages["bs1.test"] = pages(3)
        // Stored rows of an older version at the top must not end the walk.
        val (result, parts) = sync(adapter(network(1)), setOf("0x0"), recently().copy(continueHistory = true))
        assertEquals(setOf("0x0", "0x1", "0x2"), parts.flatMap { it.transactions }.map { it.hash }.toSet())
        assertFalse(result.historyTruncated)
    }

    @Test
    fun firstSyncKeepsWhatItGotWhenAPageFails() = scenario {
        onChain[1] = "0x1" to "0x1"
        nativePages["bs1.test"] = pages(4)
        respondOverride = { _, url -> if ("page=2" in url) respond("x", HttpStatusCode.NotFound) else null }
        val adapter = adapter(network(1))
        val (result, parts) = sync(adapter, options = recently())
        assertEquals(setOf("0x0", "0x1"), parts.flatMap { it.transactions }.map { it.hash }.toSet())
        assertTrue(result.historyTruncated)

        respondOverride = null
        val state = parts.mapNotNull { it.state }.single()
        val (next, nextParts) = sync(adapter, setOf("0x0", "0x1"), recently().copy(continueHistory = true, chainState = mapOf(ChainId.evm(1) to state)))
        assertTrue(setOf("0x2", "0x3").all { it in nextParts.flatMap { p -> p.transactions }.map { t -> t.hash } }, "resumes where it failed")
        assertFalse(next.historyTruncated)
    }

    @Test
    fun allListsFailingOnFirstSyncFailsTheNetworkButKeepsBalances() = scenario {
        onChain[1] = "0x1" to "0x1"
        respondOverride = { _, url -> if ("transactions" in url || "token-transfers" in url) respond("x", HttpStatusCode.NotFound) else null }
        val (result, parts) = sync(adapter(network(1)), options = recently())
        assertNull(parts.single().state)
        assertEquals(listOf(ChainId.evm(1)), result.failedChains)
    }
}
