package com.altaran.watchwallet.feature.importexport

import com.altaran.watchwallet.core.data.AddWalletService
import com.altaran.watchwallet.core.data.chain.ChainRegistry
import com.altaran.watchwallet.core.data.repository.CostBasisRepository
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.importexport.EntryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation

/** The picked file reaches the view model as a result of its own, so a lost screen cannot lose it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportFileTest {
    private val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

    private inline fun <reified T : Any> fake(vararg answers: Pair<String, Any?>): T {
        val table = answers.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when {
                method.name in table -> table[method.name]

                // Not a suspending function of the test's concern: a clear failure.
                args?.lastOrNull() !is Continuation<*> -> error("Unexpected call of ${method.name}")

                else -> null
            }
        } as T
    }

    /** Not touched while a file is read: only stored by the view model. */
    private inline fun <reified T : Any> allocated(): T {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    /** A view model with no history, as after process death behind the file picker. */
    private fun newViewModel(): ImportViewModel {
        val wallets = fake<WalletRepository>()
        val sync = allocated<SyncEngine>()
        return ImportViewModel(
            walletRepository = wallets,
            syncEngine = sync,
            addWalletService = AddWalletService(ChainRegistry(emptyList()), wallets, sync),
            pendingImport = PendingImport(),
            costBasis = allocated<CostBasisRepository>(),
        )
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aPickArrivingAtANewViewModelIsNotLost() {
        val vm = newViewModel()
        vm.onFileOpened(finishOpen(OpenSource { address.encodeToByteArray() }))
        val preview = vm.state.value.phase as ImportPhase.Preview
        assertEquals(EntryStatus.READY, preview.rows.single().entry.status)
    }

    @Test
    fun aFileTooLargeSaysSoAndALaterPickClearsIt() {
        val vm = newViewModel()
        vm.onFileOpened(finishOpen(OpenSource { null }))
        assertEquals(ImportFileProblem.TOO_LARGE, vm.state.value.fileProblem)
        assertEquals(ImportPhase.Input, vm.state.value.phase)
        vm.onFileOpened(finishOpen(OpenSource { address.encodeToByteArray() }))
        assertNull(vm.state.value.fileProblem)
    }

    @Test
    fun anUnreadableFileSaysSoAndTypingClearsIt() {
        val vm = newViewModel()
        vm.onFileOpened(finishOpen(OpenSource { throw IOException("gone") }))
        assertEquals(ImportFileProblem.UNREADABLE, vm.state.value.fileProblem)
        vm.onTextChange("b")
        assertNull(vm.state.value.fileProblem)
    }

    @Test
    fun aCancelledPickChangesNothing() {
        val vm = newViewModel()
        vm.onTextChange("kept")
        vm.onFileOpened(finishOpen(null))
        assertEquals("kept", vm.state.value.text)
        assertNull(vm.state.value.fileProblem)
    }

    @Test
    fun aSecretInAPickedFileIsRejectedLikePastedText() {
        val vm = newViewModel()
        val key = "0x" + "ab".repeat(32)
        vm.onFileOpened(finishOpen(OpenSource { key.encodeToByteArray() }))
        assertTrue(vm.state.value.phase is ImportPhase.SecretRejected)
        assertEquals("", vm.state.value.text)
    }

    @Test
    fun theRealReaderStopsAtTheLimit() {
        val source = OpenSource { limit -> readBounded(ByteArrayInputStream(ByteArray(limit + 1)), limit) }
        assertEquals(OpenOutcome.TooLarge, finishOpen(source, limit = 16))
        val fits = OpenSource { limit -> readBounded(ByteArrayInputStream(ByteArray(limit)), limit) }
        assertTrue(finishOpen(fits, limit = 16) is OpenOutcome.Loaded)
    }
}
