package com.altaran.watchwallet.core.data.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.chain.api.ChainDataException
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.chain.api.SyncOptions
import com.altaran.watchwallet.chain.api.SyncPart
import com.altaran.watchwallet.chain.api.SyncPartSink
import com.altaran.watchwallet.chain.api.SyncResult
import com.altaran.watchwallet.core.data.chain.ChainRegistry
import com.altaran.watchwallet.core.database.WatchWalletDatabase
import com.altaran.watchwallet.core.database.entity.DerivedAddressEntity
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.DerivedAddress
import com.altaran.watchwallet.core.model.TokenAmount
import com.altaran.watchwallet.core.model.WatchTarget
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Instant

private val NET_A = ChainId.evm(1)
private val NET_B = ChainId.evm(2)

private fun native(chain: ChainId) = Asset(AssetId.native(chain), chain, "N${chain.value}", "Native", 18, null)

private fun token(chain: ChainId, contract: String) = Asset(AssetId.token(chain, contract), chain, "TOK", "Token", 18, null, contract)

private fun amount(asset: Asset, raw: Long) = AssetAmount(asset, TokenAmount(BigInteger.fromLong(raw), asset.decimals))

private fun tx(chain: ChainId, hash: String, delta: Long = 1, confirmed: Boolean = true) =
    ChainTransaction(chain, hash, Instant.fromEpochSeconds(1_000), native(chain), BigInteger.fromLong(delta), null, false, confirmed)

private fun chain(id: ChainId) = Chain(id, ChainFamily.EVM, id.value, "N", 18, null, "", "", 0)

/** An adapter scripted per test. */
private class FakeAdapter(private val script: suspend (Set<String>, SyncOptions, SyncPartSink) -> SyncResult) : ChainAdapter {
    var lastOptions: SyncOptions? = null
    override val family = ChainFamily.EVM
    override val chains = listOf(chain(NET_A), chain(NET_B))
    override val supportsHistoryContinuation = true

    override suspend fun probe(target: WatchTarget) = ProbeResult(false, emptyList(), emptyList(), null)

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>) = error("the engine uses the sink")

    override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>, options: SyncOptions, sink: SyncPartSink): SyncResult {
        lastOptions = options
        return script(knownTransactions, options, sink)
    }
}

private class MemoryStates : WalletSyncStateStore {
    val states = HashMap<Long, WalletSyncState>()
    var retained: Set<Long>? = null

    override suspend fun load(walletId: Long) = states[walletId] ?: WalletSyncState()

    override suspend fun save(walletId: Long, state: WalletSyncState) {
        states[walletId] = state
    }

    override suspend fun retain(walletIds: Set<Long>) {
        retained = walletIds
    }
}

/** SDK 34: newer Robolectric SDKs fail to start on this JDK (see MigrationTest). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("TooManyFunctions") // One test per behaviour of one class.
class SyncEngineTest {
    private val db = Room.inMemoryDatabaseBuilder<WatchWalletDatabase>(RuntimeEnvironment.getApplication())
        .setDriver(BundledSQLiteDriver())
        .build()
    private val dao = db.walletDao()
    private val states = MemoryStates()

    @After
    fun close() = db.close()

    private suspend fun wallet(activeChains: String = "", truncated: Boolean = false): Long = dao.insert(
        WalletEntity(
            name = "W",
            emoji = null,
            groupId = null,
            notes = null,
            sortOrder = 0,
            archived = false,
            createdAtEpochMs = 0,
            family = "EVM",
            chainId = NET_A.value,
            targetKind = "ADDRESS",
            targetValue = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            activeChains = activeChains,
            historyTruncated = truncated,
        ),
    )

    private fun engine(adapter: ChainAdapter, prices: CurrentPriceRefresher = CurrentPriceRefresher { }) =
        SyncEngine(dao, ChainRegistry(listOf(adapter)), prices, states)

    private suspend fun balances(): Map<String, String> = dao.observeAllBalances().first().associate { it.id to it.raw }

    private suspend fun hashes(id: Long): Set<String> = dao.observeTransactions(id).first().map { it.hash }.toSet()

    @Test
    fun partsAppearWhileASlowNetworkIsStillRunning() = runBlocking {
        val id = wallet()
        val seenBalances = CompletableDeferred<Map<String, String>>()
        val adapter = FakeAdapter { _, _, sink ->
            sink.store(SyncPart(NET_A, listOf(amount(native(NET_A), 5)), balancesComplete = true, active = true))
            seenBalances.complete(balances())
            SyncResult(emptyList(), emptyList(), storedProgressively = true)
        }
        engine(adapter).syncWallet(id).join()
        assertEquals(mapOf(AssetId.native(NET_A).value to "5"), seenBalances.await())
        assertEquals(NET_A.value, dao.wallet(id)?.activeChains)
    }

    @Test
    fun aFailedNetworkKeepsItsBalancesAndHistory() = runBlocking {
        val id = wallet("${NET_A.value},${NET_B.value}")
        val tokenB = token(NET_B, "0xb")
        // Both networks are stored once.
        val first = FakeAdapter { _, _, sink ->
            sink.store(
                SyncPart(NET_A, listOf(amount(native(NET_A), 1)), balancesComplete = true, transactions = listOf(tx(NET_A, "a1")), historyFetched = true),
            )
            sink.store(
                SyncPart(
                    NET_B,
                    listOf(amount(native(NET_B), 2), amount(tokenB, 3)),
                    balancesComplete = true,
                    transactions = listOf(tx(NET_B, "b1")),
                    historyFetched = true,
                ),
            )
            SyncResult(emptyList(), emptyList(), storedProgressively = true)
        }
        engine(first).syncWallet(id).join()
        // B fails now: no part for it.
        val second = FakeAdapter { _, _, sink ->
            sink.store(SyncPart(NET_A, listOf(amount(native(NET_A), 9)), balancesComplete = true))
            SyncResult(emptyList(), emptyList(), storedProgressively = true, failedChains = listOf(NET_B))
        }
        val engine = engine(second)
        engine.syncWallet(id).join()

        assertEquals(mapOf(AssetId.native(NET_A).value to "9", AssetId.native(NET_B).value to "2", tokenB.id.value to "3"), balances())
        assertEquals(setOf("a1", "b1"), hashes(id))
        assertEquals("${NET_A.value},${NET_B.value}", dao.wallet(id)?.activeChains)
        assertNull(engine.state.value.errors[id])
        assertEquals(setOf(NET_B.value), engine.state.value.partialFailures[id])
    }

    @Test
    fun aPartialBalanceUpdateKeepsTokensAndDropsAnEmptyNativeBalance() = runBlocking {
        val id = wallet(NET_A.value)
        val tokenA = token(NET_A, "0xa")
        val first = FakeAdapter { _, _, sink ->
            sink.store(SyncPart(NET_A, listOf(amount(native(NET_A), 1), amount(tokenA, 3)), balancesComplete = true))
            SyncResult(emptyList(), emptyList(), storedProgressively = true)
        }
        engine(first).syncWallet(id).join()
        val second = FakeAdapter { _, _, sink ->
            sink.store(SyncPart(NET_A, listOf(amount(native(NET_A), 0)), balancesComplete = false, active = true))
            SyncResult(emptyList(), emptyList(), storedProgressively = true, failedChains = listOf(NET_A), allChainsFailed = true)
        }
        val engine = engine(second)
        engine.syncWallet(id).join()
        assertEquals(mapOf(tokenA.id.value to "3"), balances())
        assertNotNull("the wallet shows as failed", engine.state.value.errors[id])
        assertFalse(id in engine.state.value.partialFailures)
    }

    @Test
    fun pendingRowsAreReplacedPerChainOnlyWhenHistoryWasFetched() = runBlocking {
        val id = wallet()
        val first = FakeAdapter { _, _, sink ->
            sink.store(SyncPart(NET_A, transactions = listOf(tx(NET_A, "pa", confirmed = false)), historyFetched = true))
            sink.store(SyncPart(NET_B, transactions = listOf(tx(NET_B, "pb", confirmed = false)), historyFetched = true))
            SyncResult(emptyList(), emptyList(), storedProgressively = true)
        }
        engine(first).syncWallet(id).join()
        val second = FakeAdapter { _, _, sink ->
            sink.store(SyncPart(NET_A, transactions = listOf(tx(NET_A, "ca")), historyFetched = true))
            // Only balances for B: its pending row must stay.
            sink.store(SyncPart(NET_B, listOf(amount(native(NET_B), 1)), balancesComplete = true))
            SyncResult(emptyList(), emptyList(), storedProgressively = true)
        }
        engine(second).syncWallet(id).join()
        assertEquals(setOf("ca", "pb"), hashes(id))
    }

    @Test
    fun confirmedRowsAreNeverOverwritten() = runBlocking {
        val id = wallet()
        val first = FakeAdapter { _, _, sink ->
            sink.store(SyncPart(NET_A, transactions = listOf(tx(NET_A, "x", delta = 7)), historyFetched = true))
            SyncResult(emptyList(), emptyList(), storedProgressively = true)
        }
        engine(first).syncWallet(id).join()
        val second = FakeAdapter { _, _, sink ->
            sink.store(SyncPart(NET_A, transactions = listOf(tx(NET_A, "x", delta = 1)), historyFetched = true))
            SyncResult(emptyList(), emptyList(), storedProgressively = true)
        }
        engine(second).syncWallet(id).join()
        assertEquals("7", dao.observeTransactions(id).first().single().delta)
    }

    @Test
    fun aFailingSyncKeepsPartsAlreadyStoredAndReportsTheError() = runBlocking {
        val id = wallet()
        val adapter = FakeAdapter { _, _, sink ->
            sink.store(SyncPart(NET_A, listOf(amount(native(NET_A), 4)), balancesComplete = true))
            throw ChainDataException("down")
        }
        val engine = engine(adapter)
        engine.syncWallet(id).join()
        assertEquals("4", balances()[AssetId.native(NET_A).value])
        assertEquals("ChainDataException", engine.state.value.errors[id])
        assertNull("not synced as a whole", dao.wallet(id)?.lastSyncedAt)
    }

    @Test
    fun stateAndDiscoveryTimeAreRememberedAndPassedOn() = runBlocking {
        val id = wallet(NET_A.value, truncated = true)
        val first = FakeAdapter { _, options, sink ->
            assertNull(options.lastDiscoveryAt)
            assertTrue(options.continueHistory)
            assertEquals(setOf(NET_A), options.activeChains)
            sink.store(SyncPart(NET_A, state = "s1"))
            SyncResult(emptyList(), emptyList(), historyTruncated = false, storedProgressively = true, discoveryCompleted = true)
        }
        engine(first).syncAll(manual = true).join()
        assertEquals(true, first.lastOptions?.manual)
        assertFalse("continuation finished", dao.wallet(id)?.historyTruncated == true)
        assertEquals(setOf(id), states.retained)

        val second = FakeAdapter { _, options, _ ->
            assertNotNull(options.lastDiscoveryAt)
            assertEquals(mapOf(NET_A to "s1"), options.chainState)
            SyncResult(emptyList(), emptyList(), storedProgressively = true)
        }
        engine(second).syncWallet(id).join()
        assertNotNull("the discovery time stays", states.states[id]?.discoveredAt)
    }

    @Test
    fun anAdapterWithoutProgressStillStoresEverythingAtOnce() = runBlocking {
        val id = wallet()
        val adapter = object : ChainAdapter {
            override val family = ChainFamily.EVM
            override val chains = listOf(chain(NET_A))

            override suspend fun probe(target: WatchTarget) = ProbeResult(false, emptyList(), emptyList(), null)

            override suspend fun sync(target: WatchTarget, knownTransactions: Set<String>) =
                SyncResult(listOf(amount(native(NET_A), 6)), listOf(tx(NET_A, "w")), activeChains = listOf(NET_A))
        }
        engine(adapter).syncWallet(id).join()
        assertEquals("6", balances()[AssetId.native(NET_A).value])
        assertEquals(setOf("w"), hashes(id))
        assertEquals(NET_A.value, dao.wallet(id)?.activeChains)
    }

    @Test
    fun pricesDoNotWaitForWallets() = runBlocking {
        wallet()
        val pricesAsked = CompletableDeferred<Unit>()
        val adapter = FakeAdapter { _, _, _ ->
            // A wallet that only finishes once prices were requested: with the old order this hangs.
            pricesAsked.await()
            SyncResult(emptyList(), emptyList(), storedProgressively = true)
        }
        engine(adapter, CurrentPriceRefresher { pricesAsked.complete(Unit) }).syncAll().join()
        assertTrue(pricesAsked.isCompleted)
    }

    @Test
    fun walletHealthTellsWhatFailed() = runBlocking {
        val cases = listOf(
            "all fine" to (SyncResult(emptyList(), emptyList(), storedProgressively = true) to WalletSyncHealth.OK),
            "one network of several" to
                (SyncResult(emptyList(), emptyList(), storedProgressively = true, failedChains = listOf(NET_B)) to WalletSyncHealth.PARTIAL),
            "only network unreachable" to
                (
                    SyncResult(emptyList(), emptyList(), storedProgressively = true, failedChains = listOf(NET_A), allChainsFailed = true) to
                        WalletSyncHealth.FAILED
                ),
            "only network, blocked indexer" to (
                SyncResult(
                    emptyList(),
                    emptyList(),
                    storedProgressively = true,
                    failedChains = listOf(NET_A),
                    allChainsFailed = true,
                    nativeCurrentChains = listOf(NET_A),
                ) to
                    WalletSyncHealth.NATIVE_ONLY
            ),
        )
        cases.forEach { (name, case) ->
            val id = wallet()
            val engine = engine(FakeAdapter { _, _, _ -> case.first })
            engine.syncWallet(id).join()
            assertEquals(name, case.second, engine.state.value.healthOf(id))
        }
    }

    @Test
    fun aBalanceListCutOffByALimitKeepsWhatItDoesNotName() = runBlocking {
        val id = wallet()
        val kept = token(NET_A, "kept")
        val sold = token(NET_A, "sold")
        var result = SyncResult(listOf(amount(native(NET_A), 5), amount(kept, 7), amount(sold, 9)), emptyList())
        val engine = engine(FakeAdapter { _, _, _ -> result })
        engine.syncWallet(id).join()
        result = SyncResult(listOf(amount(native(NET_A), 6), amount(sold, 0)), emptyList()).withIncompleteBalances(NET_A)
        engine.syncWallet(id).join()
        assertEquals(mapOf(AssetId.native(NET_A).value to "6", kept.id.value to "7"), balances())
        assertEquals(WalletSyncHealth.NATIVE_ONLY, engine.state.value.healthOf(id))
    }

    @Test
    fun correctedTransactionsReplaceStoredOnesButNewOnesDoNot() = runBlocking {
        val id = wallet()
        var result = SyncResult(emptyList(), listOf(tx(NET_A, "h", delta = 1)))
        val engine = engine(FakeAdapter { _, _, _ -> result })
        engine.syncWallet(id).join()
        result = SyncResult(emptyList(), listOf(tx(NET_A, "h", delta = 2)))
        engine.syncWallet(id).join()
        assertEquals("1", dao.observeTransactions(id).first().single().delta)
        result = SyncResult(emptyList(), emptyList(), correctedTransactions = listOf(tx(NET_A, "h", delta = 3)))
        engine.syncWallet(id).join()
        assertEquals("3", dao.observeTransactions(id).first().single().delta)
    }

    @Test
    fun aManualRefreshForgetsFailingHostsEverywhere() = runBlocking {
        val id = wallet()
        var chainResets = 0
        var priceResets = 0
        val adapter = object : ChainAdapter by FakeAdapter({ _, _, _ -> SyncResult(emptyList(), emptyList(), storedProgressively = true) }) {
            override suspend fun resetHostHealth() {
                chainResets++
            }
        }
        val prices = object : CurrentPriceRefresher {
            override suspend fun refreshCurrentPrices() = Unit

            override suspend fun resetHostHealth() {
                priceResets++
            }
        }
        engine(adapter, prices).syncAll(manual = false).join()
        assertEquals(0, chainResets)
        engine(adapter, prices).syncAll(manual = true).join()
        assertEquals(1, chainResets)
        assertEquals(1, priceResets)
        assertNotNull(dao.wallet(id))
    }

    @Test
    fun aServerCannotAddAddressesToAnAddressWalletAndOldRowsGo() = runBlocking {
        val id = wallet()
        dao.storeSync(
            walletId = id,
            assets = emptyList(),
            balances = emptyList(),
            transactions = emptyList(),
            derived = listOf(DerivedAddressEntity(id, "addr1fromversion1", false, 0, true)),
            activeChains = "",
            syncedAt = 0,
            truncated = false,
            balanceChains = null,
            refreshedAssets = null,
        )
        assertEquals(1, dao.observeDerivedAddresses(id).first().size)
        val result = SyncResult(emptyList(), emptyList(), derivedAddresses = listOf(DerivedAddress("addr1fromserver", false, 0, used = true)))
        engine(FakeAdapter { _, _, _ -> result }).syncWallet(id).join()
        assertEquals(emptyList<DerivedAddressEntity>(), dao.observeDerivedAddresses(id).first())
    }

    @Test
    fun extendedKeyWalletsPassTheAddressesFoundBefore() = runBlocking {
        val id = dao.insert(
            WalletEntity(
                name = "X",
                emoji = null,
                groupId = null,
                notes = null,
                sortOrder = 0,
                archived = false,
                createdAtEpochMs = 0,
                family = "UTXO",
                chainId = NET_A.value,
                targetKind = "XPUB",
                targetValue = "xpub",
                scriptType = "P2PKH",
                gapLimit = 20,
            ),
        )
        val result =
            SyncResult(
                emptyList(),
                emptyList(),
                derivedAddresses = listOf(DerivedAddress("a", false, 0, used = true), DerivedAddress("b", false, 1, used = false)),
            )
        val adapter = FakeAdapter { _, _, _ -> result }
        val engine = engine(adapter)
        engine.syncWallet(id).join()
        assertEquals(emptySet<String>(), adapter.lastOptions?.usedAddresses)
        engine.syncWallet(id).join()
        assertEquals(setOf("a"), adapter.lastOptions?.usedAddresses)
    }

    /** The message of an HTTP client's exception holds the URL with the address. */
    @Test
    fun aFailureKeepsItsTypeButNotItsMessage() = runBlocking {
        val id = wallet()
        val engine = engine(FakeAdapter { _, _, _ -> throw IllegalStateException("GET https://host.test/address/0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed") })
        engine.syncAll().join()
        assertEquals("IllegalStateException", engine.state.value.errors[id])
    }

    private class Rounds : ServerFailureLog {
        val seen = mutableListOf<Pair<Set<String>, Boolean>>()

        override suspend fun round(failed: Set<String>, connected: Boolean) {
            seen += failed to connected
        }
    }

    private fun counted(adapter: ChainAdapter, rounds: Rounds, prices: CurrentPriceRefresher = CurrentPriceRefresher { }) =
        SyncEngine(dao, ChainRegistry(listOf(adapter)), prices, states, failures = rounds)

    @Test
    fun aRoundReportsChainsThatFailedOutright() = runBlocking {
        wallet()
        val rounds = Rounds()
        val adapter = FakeAdapter { _, _, _ -> SyncResult(emptyList(), emptyList(), failedChains = listOf(NET_B)) }
        counted(adapter, rounds).syncAll().join()
        assertEquals(listOf(setOf(NET_B.value) to true), rounds.seen)
    }

    /** A token list over a limit or a failing explorer is no dead server: the native balance came through. */
    @Test
    fun aChainWithACurrentNativeBalanceIsNoServerFailure() = runBlocking {
        wallet()
        val rounds = Rounds()
        val adapter = FakeAdapter { _, _, _ ->
            SyncResult(emptyList(), emptyList(), failedChains = listOf(NET_A), allChainsFailed = true, nativeCurrentChains = listOf(NET_A))
        }
        counted(adapter, rounds).syncAll().join()
        assertEquals(listOf(emptySet<String>() to true), rounds.seen)
    }

    /** One wallet, everything failing and no price either: that looks like no connection. */
    @Test
    fun aRoundWithNothingReachedIsNotConnected() = runBlocking {
        wallet(activeChains = NET_A.value)
        val rounds = Rounds()
        counted(FakeAdapter { _, _, _ -> throw IllegalStateException("no route") }, rounds).syncAll().join()
        assertEquals(listOf(setOf(NET_A.value) to false), rounds.seen)
    }

    /** A dead server of a single wallet: the prices arriving show that the device is online. */
    @Test
    fun aPriceThatArrivedProvesTheConnection() = runBlocking {
        wallet(activeChains = NET_A.value)
        val rounds = Rounds()
        val prices = object : CurrentPriceRefresher {
            override suspend fun refreshCurrentPrices() = Unit

            override suspend fun reachedNetworkRecently() = true
        }
        counted(FakeAdapter { _, _, _ -> throw IllegalStateException("no route") }, rounds, prices).syncAll().join()
        assertEquals(listOf(setOf(NET_A.value) to true), rounds.seen)
    }

    @Test
    fun aSyncOfOneWalletIsNoRound() = runBlocking {
        val id = wallet()
        val rounds = Rounds()
        counted(FakeAdapter { _, _, _ -> throw IllegalStateException("no route") }, rounds).syncWallet(id).join()
        assertEquals(emptyList<Pair<Set<String>, Boolean>>(), rounds.seen)
    }

    @Test
    fun aRoundWithoutWalletsClearsTheCounts() = runBlocking {
        val rounds = Rounds()
        counted(FakeAdapter { _, _, _ -> error("no wallets") }, rounds).syncAll().join()
        assertEquals(listOf(emptySet<String>() to true), rounds.seen)
    }
}
