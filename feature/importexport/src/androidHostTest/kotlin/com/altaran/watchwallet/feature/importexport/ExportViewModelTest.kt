package com.altaran.watchwallet.feature.importexport

import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.model.Wallet
import com.altaran.watchwallet.core.model.WalletGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Robolectric for the real encryption, which uses `android.util.Base64`. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportViewModelTest {
    /** Answers the listed members; a call of anything else means the test needs a closer look. */
    private inline fun <reified T : Any> fake(vararg answers: Pair<String, Any?>): T {
        val table = answers.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            if (method.name in table) table[method.name] else error("Unexpected call of ${method.name}")
        } as T
    }

    private fun viewModel() = ExportViewModel(
        walletRepository = fake<WalletRepository>(
            "getGroups" to flowOf(emptyList<WalletGroup>()),
            "getActiveWallets" to flowOf(emptyList<Wallet>()),
        ),
        walletValuesIn = { flowOf(emptyMap()) },
        settingsRepository = fake<SettingsRepository>(),
        userCosts = { emptyMap() },
    )

    private fun ExportViewModel.built(share: Boolean): PreparedExport = runBlocking {
        prepare(share)
        assertNotNull(state.first { !it.busy }.prepared)
        state.value.prepared!!
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The screen is gone after a rotation, this view model is not. */
    @Test
    fun theFileContentWaitsInTheViewModelForThePicker() {
        val vm = viewModel()
        val prepared = vm.built(share = false)
        vm.onPreparedHandled()
        assertEquals(prepared.content, vm.takePendingSave())
    }

    @Test
    fun theContentIsHandedOverOnlyOnce() {
        val vm = viewModel()
        vm.built(share = false)
        assertNotNull(vm.takePendingSave())
        assertNull(vm.takePendingSave())
    }

    @Test
    fun afterProcessDeathThereIsNoContentAndTheUserIsTold() {
        val recreated = viewModel()
        assertNull(recreated.takePendingSave())
        recreated.onSaveFinished(SaveOutcome.FAILED)
        assertEquals(ExportMessage.SAVE_FAILED, recreated.state.value.message)
    }

    @Test
    fun sharingLeavesNothingPending() {
        val vm = viewModel()
        vm.built(share = true)
        assertNull(vm.takePendingSave())
    }

    @Test
    fun outcomesBecomeMessages() {
        val vm = viewModel()
        vm.onSaveFinished(SaveOutcome.SAVED)
        assertEquals(ExportMessage.SAVED, vm.state.value.message)
        vm.onSaveFinished(SaveOutcome.CANCELLED)
        assertNull(vm.state.value.message)
    }

    @Test
    fun aMistypedPasswordKeepsTheExportFromStarting() {
        val vm = viewModel()
        vm.setPassword("correct horse battery")
        vm.setPasswordConfirmation("correct horse batterz")
        assertEquals(PasswordProblem.MISMATCH, vm.state.value.passwordProblem)
        vm.prepare(share = false)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.prepared)
        assertNull(vm.takePendingSave())
    }

    @Test
    fun aShortPasswordKeepsTheExportFromStarting() {
        val vm = viewModel()
        vm.setPassword("short")
        vm.setPasswordConfirmation("short")
        assertEquals(PasswordProblem.TOO_SHORT, vm.state.value.passwordProblem)
        vm.prepare(share = true)
        assertNull(vm.state.value.prepared)
    }

    @Test
    fun aConfirmedPasswordEncryptsAndIsThenForgotten() {
        val password = "correct horse battery"
        val vm = viewModel()
        vm.setPassword(password)
        vm.setPasswordConfirmation(password)
        val prepared = vm.built(share = false)
        assertTrue(ExportCrypto.isEncrypted(prepared.content))
        assertNotNull(ExportCrypto.decrypt(prepared.content, password))
        assertEquals("", vm.state.value.password)
        assertEquals("", vm.state.value.passwordConfirmation)
    }

    @Test
    fun otherFormatsIgnoreThePasswordFields() {
        val vm = viewModel()
        vm.setPassword("short")
        vm.setFormat(ExportFormatOption.TEXT)
        assertNull(vm.state.value.passwordProblem)
    }

    @Test
    fun passwordRules() {
        val long = "x".repeat(MIN_EXPORT_PASSWORD_LENGTH)
        assertNull("no password, no encryption", passwordProblem("", ""))
        assertEquals(PasswordProblem.TOO_SHORT, passwordProblem("x".repeat(MIN_EXPORT_PASSWORD_LENGTH - 1), ""))
        assertEquals(PasswordProblem.MISMATCH, passwordProblem(long, ""))
        assertEquals(PasswordProblem.MISMATCH, passwordProblem(long, long + "y"))
        assertEquals(PasswordProblem.MISMATCH, passwordProblem("", "typed in the wrong field"))
        assertNull(passwordProblem(long, long))
    }
}
