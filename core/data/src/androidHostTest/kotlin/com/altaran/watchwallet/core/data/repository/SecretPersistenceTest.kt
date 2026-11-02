package com.altaran.watchwallet.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.altaran.watchwallet.core.data.platform.AppInfo
import com.altaran.watchwallet.core.database.WatchWalletDatabase
import com.altaran.watchwallet.core.database.entity.WalletEntity
import com.altaran.watchwallet.core.datastore.SettingsDataSource
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.model.UserSettings
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.security.SecretKind
import com.altaran.watchwallet.core.security.SecretRejectedException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder<WatchWalletDatabase>(RuntimeEnvironment.getApplication())
        .setDriver(BundledSQLiteDriver())
        .build()
    private val dao = db.walletDao()
    private val repository = DefaultWalletRepository(dao)
    private val hexValue = "0x" + "a1".repeat(32)
    private val seed = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val btc = WatchTarget.Address(ChainFamily.UTXO, ChainId.BITCOIN, "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")

    @After
    fun close() = db.close()

    private fun rejected(block: suspend () -> Unit) {
        val e = assertThrows(SecretRejectedException::class.java) { runBlocking { block() } }
        assertFalse("the message must not carry the input", e.message.orEmpty().contains("abandon"))
    }

    @Test
    fun everyStoringFunctionRejectsWithATypedExceptionThatCarriesNoInput() {
        rejected { repository.addWallet(btc, seed, emptyList()) }
        rejected { repository.addWallet(btc, "Name", emptyList(), notes = seed) }
        rejected { repository.createGroup(seed) }
        runBlocking {
            val id = repository.addWallet(btc, "Name", emptyList())
            rejected { repository.rename(id, seed) }
            assertEquals("Name", repository.activeWallets.first().single().name)
        }
    }

    @Test
    fun aHexKeyAsTheTargetOfAnyOtherChainIsNotStored() {
        rejected { repository.addWallet(WatchTarget.Address(ChainFamily.EVM, ChainId.evm(1), hexValue), "X", emptyList()) }
        rejected { repository.addWallet(WatchTarget.Address(ChainFamily.UTXO, ChainId.BITCOIN, hexValue), "X", emptyList()) }
        rejected { repository.addWallet(WatchTarget.Address(ChainFamily.SUI, ChainId("sui"), "0x" + "a1".repeat(64)), "X", emptyList()) }
    }

    @Test
    fun suiAndAptosAddressesAreStoredAndExistingOnesKeepLoading() = runBlocking {
        val sui = WatchTarget.Address(ChainFamily.SUI, ChainId("sui"), hexValue)
        repository.addWallet(sui, "Sui", emptyList())
        // A wallet stored by an older version, never validated again when it is read.
        dao.insert(
            WalletEntity(
                name = "Aptos",
                emoji = null,
                groupId = null,
                notes = null,
                sortOrder = 2,
                archived = false,
                createdAtEpochMs = 0,
                family = ChainFamily.APTOS.name,
                chainId = "aptos",
                targetKind = KIND_ADDRESS,
                targetValue = "0x" + "b2".repeat(32),
                scriptType = null,
                gapLimit = null,
                activeChains = "",
            ),
        )
        val wallets = repository.activeWallets.first()
        assertEquals(listOf("Sui", "Aptos"), wallets.map { it.name })
        // A rename of such a wallet checks the name only.
        repository.rename(wallets.first().id, "New name")
        assertEquals("New name", repository.activeWallets.first().first().name)
        assertEquals(wallets.first().id, repository.findExisting(sui))
    }

    private fun settingsSource() = SettingsDataSource(
        PreferenceDataStoreFactory.create(produceFile = { File(createTempDirectory().toFile(), "s.preferences_pb") }),
    ) { QuoteCurrency.DEFAULT }

    @Test
    fun screenshotBlockingIsOnUnlessTheUserTurnedItOff() = runBlocking {
        assertTrue(UserSettings(QuoteCurrency.DEFAULT).secureScreen)
        val source = settingsSource()
        // New install, and an existing install whose user never touched the switch: no stored value.
        assertTrue(source.settings.first().secureScreen)
        // Other settings do not write it.
        source.initializeDefaults()
        source.setDiscreetMode(true)
        source.setNetwork(source.settings.first().network)
        assertTrue(source.settings.first().secureScreen)
        // The user's choice stays, in both directions.
        source.setSecureScreen(false)
        assertFalse(source.settings.first().secureScreen)
        source.setSecureScreen(true)
        assertTrue(source.settings.first().secureScreen)
    }

    @Test
    fun theWidgetHidesAmountsWithTheLockOrDiscreetMode() {
        val base = UserSettings(QuoteCurrency.DEFAULT)
        assertFalse(base.widgetHidesAmounts)
        assertTrue(base.copy(appLock = true).widgetHidesAmounts)
        assertTrue(base.copy(discreetMode = true).widgetHidesAmounts)
    }

    @Test
    fun turningTheLockOffNeedsAuthentication() = runBlocking {
        val repository = DefaultSettingsRepository(settingsSource())
        repository.setAppLock(true)
        assertFalse(repository.changeAppLock(false) { false })
        assertTrue(repository.settings.first().appLock)
        assertTrue(repository.changeAppLock(false) { true })
        assertFalse(repository.settings.first().appLock)
        // Turning it on needs nothing: the prompt comes the next time the app opens.
        assertTrue(repository.changeAppLock(true) { error("no prompt to turn the lock on") })
        assertTrue(repository.settings.first().appLock)
    }

    @Test
    fun thePrivacyLinkPointsToTheTagOfThisVersion() {
        val info = AppInfo("1.2.3-debug", 1, "https://github.com/altaraneu/watchwallet", false)
        assertEquals("https://github.com/altaraneu/watchwallet/blob/v1.2.3/PRIVACY.md", info.privacyPolicyUrl)
        assertEquals("https://github.com/altaraneu/watchwallet/blob/v1.2.3/PRIVACY.md", info.copy(versionName = "1.2.3").privacyPolicyUrl)
    }

    @Test
    fun rejectedKindIsReported() {
        val e = assertThrows(SecretRejectedException::class.java) { runBlocking { repository.createGroup("0x" + "a1".repeat(32)) } }
        assertEquals(SecretKind.HEX_PRIVATE_KEY, e.kind)
    }
}
