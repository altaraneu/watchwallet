package com.altaran.watchwallet.feature.addwallet

import com.altaran.watchwallet.chain.api.ProbeResult
import com.altaran.watchwallet.core.classifier.AddressClassifier
import com.altaran.watchwallet.core.classifier.Candidate
import com.altaran.watchwallet.core.classifier.Classification
import com.altaran.watchwallet.core.data.AddWalletOperations
import com.altaran.watchwallet.core.data.CandidatePreview
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WalletGroup
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.security.SecretKind
import com.altaran.watchwallet.core.security.SecretRejectedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeOperations : AddWalletOperations {
    val probed = mutableListOf<WatchTarget>()
    var saveThrows: SecretKind? = null
    var saved = 0
    override val groups = flowOf(listOf(WalletGroup(1, "Family", 0)))

    override fun classify(input: String) = AddressClassifier.classify(input).let {
        if (it is Classification.Unsupported) Classification.Unrecognized else it
    }

    override fun confirmedTarget(value: String, family: ChainFamily): WatchTarget? =
        if (value.startsWith("0x")) WatchTarget.Address(family, ChainId(family.name.lowercase()), value.lowercase()) else null

    override fun isSupported(family: ChainFamily) = true

    override fun chainName(id: ChainId) = id.value

    override suspend fun probe(candidates: List<Candidate>): List<CandidatePreview> {
        probed += candidates.map { it.target }
        return candidates.map { CandidatePreview(it, ProbeResult(true, emptyList(), emptyList(), null), false, null) }
    }

    override suspend fun save(target: WatchTarget, name: String, probe: ProbeResult?, groupName: String?, subtractFrom: Long?): Long {
        saveThrows?.let { throw SecretRejectedException(it) }
        saved++
        return 7
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AddWalletViewModelTest {
    private val hexValue = "0x" + "a1".repeat(32)
    private val seed = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val operations = FakeOperations()
    private lateinit var viewModel: AddWalletViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = AddWalletViewModel(operations)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val state get() = viewModel.state.value

    private fun assertNoTrace(value: String) {
        val all = state.toString()
        assertFalse("the rejected value must not stay in the state", all.contains(value.take(12)))
        assertEquals("", state.input)
    }

    @Test
    fun aSecretIsRejectedOnEveryChangeOfTheField() {
        listOf(seed, hexValue, "0c28fca386c7a227600b2fe50b7cae11ec86d3bf1fbe471be89827e19d72aa1d").forEach { secret ->
            assertTrue(viewModel.onInputChange(secret))
            assertTrue(state.phase is AddWalletPhase.SecretRejected)
            assertNoTrace(secret)
        }
        assertEquals(emptyList<WatchTarget>(), operations.probed)
        assertFalse(viewModel.onInputChange("bc1q"))
        assertEquals("bc1q", state.input)
    }

    @Test
    fun theSameCheckRunsOnPasteAndForQrContent() {
        assertTrue(viewModel.onPaste(hexValue))
        assertNoTrace(hexValue)
        viewModel.onClear()
        viewModel.onScanned(hexValue, null)
        assertTrue(state.phase is AddWalletPhase.SecretRejected)
        assertNoTrace(hexValue)
        assertEquals(emptyList<WatchTarget>(), operations.probed)
    }

    @Test
    fun aBinaryQrPayloadOfASeedLengthIsRejected() {
        viewModel.onScanned("garbled", ByteArray(16) { (it * 11 + 128).toByte() })
        assertTrue(state.phase is AddWalletPhase.SecretRejected)
        assertEquals("", state.input)
    }

    @Test
    fun namesAndGroupsAreCheckedWithoutLosingTheScreen() {
        assertTrue(viewModel.onNameChange(seed))
        assertEquals("", state.name)
        assertTrue(state.fieldRejected)
        assertFalse(viewModel.onNameChange("Savings"))
        assertFalse(state.fieldRejected)
        assertTrue(viewModel.onGroupChange(hexValue))
        assertEquals("", state.groupName)
        assertFalse(state.toString().contains("a1a1a1"))
    }

    @Test
    fun theHexFormIsAcceptedOnlyAfterChainAndWarning() {
        // Not at any earlier step.
        viewModel.onStartDeliberate()
        assertTrue(viewModel.onInputChange(hexValue))
        viewModel.onStartDeliberate()
        viewModel.onPickDeliberateChain(ChainFamily.SUI)
        assertEquals(AddWalletPhase.DeliberateWarning(ChainFamily.SUI), state.phase)
        assertTrue(viewModel.onInputChange(hexValue))
        assertEquals(emptyList<WatchTarget>(), operations.probed)

        viewModel.onStartDeliberate()
        viewModel.onPickDeliberateChain(ChainFamily.SUI)
        viewModel.onAcceptDeliberateWarning()
        assertFalse(viewModel.onInputChange(hexValue))
        assertEquals(hexValue, state.input)
        assertEquals(AddWalletPhase.DeliberateEntry(ChainFamily.SUI), state.phase)
        assertEquals(emptyList<WatchTarget>(), operations.probed)

        viewModel.onSubmit()
        assertEquals(listOf<WatchTarget>(WatchTarget.Address(ChainFamily.SUI, ChainId("sui"), hexValue)), operations.probed)
    }

    @Test
    fun otherSecretsStayRejectedOnTheDeliberateRoute() {
        viewModel.onStartDeliberate()
        viewModel.onPickDeliberateChain(ChainFamily.APTOS)
        viewModel.onAcceptDeliberateWarning()
        listOf(seed, "0c28fca386c7a227600b2fe50b7cae11ec86d3bf1fbe471be89827e19d72aa1d", "key $hexValue").forEach { secret ->
            assertTrue(secret, viewModel.onInputChange(secret))
            assertNoTrace(secret)
            viewModel.onStartDeliberate()
            viewModel.onPickDeliberateChain(ChainFamily.APTOS)
            viewModel.onAcceptDeliberateWarning()
        }
        assertEquals(emptyList<WatchTarget>(), operations.probed)
    }

    @Test
    fun aRejectionWhileSavingIsAWarningNotACrash() {
        operations.saveThrows = SecretKind.MNEMONIC
        viewModel.onSubmit("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")
        assertTrue(state.phase is AddWalletPhase.Preview)
        viewModel.onSave("Default")
        assertEquals(AddWalletPhase.SecretRejected(SecretKind.MNEMONIC), state.phase)
        assertEquals(null, state.savedWalletId)
        assertFalse(state.saving)
        assertEquals(listOf("Family"), state.groups)
    }

    @Test
    fun aNormalAddressStillWorks() {
        viewModel.onSubmit("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")
        viewModel.onSave("Default")
        assertEquals(1, operations.saved)
        assertEquals(7L, state.savedWalletId)
    }
}
