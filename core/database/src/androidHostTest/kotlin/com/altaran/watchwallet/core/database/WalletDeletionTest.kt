package com.altaran.watchwallet.core.database

import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.altaran.watchwallet.core.database.entity.AssetEntity
import com.altaran.watchwallet.core.database.entity.DerivedAddressEntity
import com.altaran.watchwallet.core.database.entity.PriceCurrentEntity
import com.altaran.watchwallet.core.database.entity.PriceDetailEntity
import com.altaran.watchwallet.core.database.entity.PricePointEntity
import com.altaran.watchwallet.core.database.entity.WalletBalanceEntity
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.database.entity.WatchlistEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Against a real database file, built like in the app. SDK 34: see [MigrationTest]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalletDeletionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val file: File = context.getDatabasePath("deletion-test.db")
    private lateinit var db: WatchWalletDatabase
    private val dao get() = db.walletDao()

    @Before
    fun setUp() {
        file.parentFile?.mkdirs()
        files().forEach { it.delete() }
        db = Room.databaseBuilder<WatchWalletDatabase>(context, file.absolutePath).buildWatchWalletDatabase()
    }

    @After
    fun tearDown() {
        db.close()
        files().forEach { it.delete() }
    }

    private fun files() = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"))

    private suspend fun addWallet(name: String, kind: String = "ADDRESS", value: String = name) = dao.insert(
        WalletEntity(
            name = name,
            emoji = null,
            groupId = null,
            notes = null,
            sortOrder = 0,
            archived = false,
            createdAtEpochMs = 0,
            family = "CARDANO",
            chainId = "cardano",
            targetKind = kind,
            targetValue = value,
        ),
    )

    private fun asset(id: String, priceId: String? = id) = AssetEntity(id, "cardano", id, id, 6, priceId, null)

    private suspend fun hold(wallet: Long, vararg assets: AssetEntity) = dao.storeSync(
        walletId = wallet,
        assets = assets.toList(),
        balances = assets.map { WalletBalanceEntity(wallet, it.id, "1", 0) },
        transactions = emptyList(),
        derived = null,
        activeChains = "",
        syncedAt = 0,
        truncated = false,
        balanceChains = null,
    )

    private suspend fun cachePrice(priceId: String) {
        db.priceDao().upsertCurrent(listOf(PriceCurrentEntity(priceId, "usd", 1.0, null, 0)))
        db.priceDao().insertPoints(listOf(PricePointEntity(priceId, "usd", 1, 1.0)))
        db.priceDetailDao().insert(listOf(PriceDetailEntity(priceId, "usd", 60, 1, 1.0)))
    }

    private suspend fun assetIds() = dao.observeAssets().first().map { it.id }.toSet()

    private suspend fun cachedPrice(priceId: String): Boolean {
        val current = db.priceDao().observeCurrent("usd").first().any { it.priceId == priceId }
        val point = db.priceDao().latestPointTime(priceId, "usd") != null
        val detail = db.priceDetailDao().times(priceId, "usd", 60, 0, 10).isNotEmpty()
        assertEquals("the three caches stay in step for $priceId", current, point)
        assertEquals("the three caches stay in step for $priceId", current, detail)
        return current
    }

    @Test
    fun removingAWalletRemovesWhatOnlyItUsed() = runBlocking {
        val wallet = addWallet("one")
        hold(wallet, asset("cardano:rare"))
        cachePrice("cardano:rare")

        dao.delete(wallet)

        assertEquals(emptySet<String>(), assetIds())
        assertFalse(cachedPrice("cardano:rare"))
    }

    @Test
    fun whatAnotherWalletStillUsesStays() = runBlocking {
        val first = addWallet("one")
        val second = addWallet("two")
        val shared = asset("cardano:shared")
        hold(first, shared, asset("cardano:only-first"))
        hold(second, shared)
        dao.setAssetVisibility("cardano:shared", false)
        cachePrice("cardano:shared")
        cachePrice("cardano:only-first")

        dao.delete(first)

        assertEquals(setOf("cardano:shared"), assetIds())
        assertEquals("the user's choice survives", false, dao.observeAssets().first().single().userVisible)
        assertEquals(1, dao.observeAllBalances().first().size)
        assertEquals(true, cachedPrice("cardano:shared"))
        assertFalse(cachedPrice("cardano:only-first"))
    }

    @Test
    fun aWatchedCoinKeepsItsPrices() = runBlocking {
        val wallet = addWallet("one")
        hold(wallet, asset("bitcoin"))
        cachePrice("bitcoin")
        db.priceDao().addToWatchlist(WatchlistEntity("bitcoin", "BTC", "Bitcoin", 0))

        dao.delete(wallet)

        assertEquals(emptySet<String>(), assetIds())
        assertEquals(true, cachedPrice("bitcoin"))
    }

    @Test
    fun anAssetWithoutAPriceIdDoesNotBlockThePruning() = runBlocking {
        val first = addWallet("one")
        val second = addWallet("two")
        hold(first, asset("cardano:rare"))
        hold(second, asset("cardano:unpriced", priceId = null))
        cachePrice("cardano:rare")

        dao.delete(first)

        assertFalse("a NULL in NOT IN would keep everything", cachedPrice("cardano:rare"))
    }

    @Test
    fun deletingEverythingAlsoRemovesAssetsAndPrices() = runBlocking {
        hold(addWallet("one"), asset("cardano:a"))
        hold(addWallet("two"), asset("cardano:b"))
        cachePrice("cardano:a")
        cachePrice("cardano:b")
        cachePrice("bitcoin")
        db.priceDao().addToWatchlist(WatchlistEntity("bitcoin", "BTC", "Bitcoin", 0))

        dao.deleteAllWallets()

        assertEquals(emptySet<String>(), assetIds())
        assertFalse(cachedPrice("cardano:a"))
        assertEquals("the watchlist stays, as PRIVACY.md says", true, cachedPrice("bitcoin"))
    }

    @Test
    fun deletedTextIsGoneFromTheFiles() = runBlocking {
        val wallet = addWallet(CANARY_NAME, value = CANARY_ADDRESS)
        hold(wallet, asset(CANARY_ASSET))
        cachePrice(CANARY_ASSET)
        assertNotNull(dao.wallet(wallet))

        dao.delete(wallet)
        db.scrubDeletedData()

        val bytes = files().filter { it.exists() }.map { it.readBytes().toString(Charsets.ISO_8859_1) }
        listOf(CANARY_NAME, CANARY_ADDRESS, CANARY_ASSET).forEach { canary ->
            bytes.forEach { content -> assertFalse("$canary is still on disk", canary in content) }
        }
    }

    @Test
    fun scrubbingKeepsEverythingElse() = runBlocking {
        val keep = addWallet("keep")
        val remove = addWallet("remove")
        hold(keep, asset("cardano:kept"))
        dao.delete(remove)
        db.scrubDeletedData()
        assertEquals(listOf("keep"), dao.activeWallets().map { it.name })
        assertEquals(setOf("cardano:kept"), assetIds())
    }

    /** Each connection has its own setting, and a query can open a reader before any writer. */
    @Test
    fun everyConnectionOverwritesDeletedContent() = runBlocking {
        val query = "PRAGMA secure_delete"
        val writer = db.useWriterConnection { it.usePrepared(query) { row -> row.step() && row.getLong(0) == 1L } }
        val reader = db.useReaderConnection { it.usePrepared(query) { row -> row.step() && row.getLong(0) == 1L } }
        assertEquals(true, writer)
        assertEquals(true, reader)
    }

    /** Such a row is what a server reported, and must not cause a balance to be subtracted. */
    @Test
    fun onlyAnExtendedKeyWalletCountsAsContainingAnAddress() = runBlocking {
        val stake = addWallet("stake", kind = "ADDRESS", value = "stake1x")
        val xpub = addWallet("xpub", kind = "XPUB", value = "xpub1")
        fun row(wallet: Long, address: String) = DerivedAddressEntity(wallet, address, false, 0, true)
        dao.storeSync(stake, emptyList(), emptyList(), emptyList(), listOf(row(stake, "addr1server")), "", 0, false, null)
        dao.storeSync(xpub, emptyList(), emptyList(), emptyList(), listOf(row(xpub, "addr1derived")), "", 0, false, null)

        assertNull(dao.findByDerivedAddress("CARDANO", "addr1server"))
        assertEquals(xpub, dao.findByDerivedAddress("CARDANO", "addr1derived")?.id)
    }

    private companion object {
        const val CANARY_NAME = "canary-wallet-name-3f9a"
        const val CANARY_ADDRESS = "canary-address-77c1"
        const val CANARY_ASSET = "canary-asset-b20e"
    }
}
