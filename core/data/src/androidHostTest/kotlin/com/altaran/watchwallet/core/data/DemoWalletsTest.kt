package com.altaran.watchwallet.core.data

import com.altaran.watchwallet.core.model.WatchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoWalletsTest {
    private fun chainOf(target: WatchTarget?): String? = when (target) {
        is WatchTarget.Address -> target.chainId.value
        is WatchTarget.ExtendedPublicKey -> target.chainId.value
        null -> null
    }

    @Test
    fun everyDemoEntryResolvesToAWatchTarget() {
        val unresolved = DemoWallets.ENTRIES.filter { DemoWallets.resolve(it) == null }.map { it.name }
        assertTrue("Unresolved demo entries: $unresolved", unresolved.isEmpty())
    }

    @Test
    fun demoEntriesCoverEveryChainFamilyOnce() {
        val targets = DemoWallets.ENTRIES.mapNotNull { DemoWallets.resolve(it) }
        assertEquals(targets.size, targets.distinct().size)
        val carolChains = DemoWallets.ENTRIES.filter { it.group == "Carol" }.mapNotNull { chainOf(DemoWallets.resolve(it)) }
        assertEquals(carolChains.size, carolChains.toSet().size)
    }
}
