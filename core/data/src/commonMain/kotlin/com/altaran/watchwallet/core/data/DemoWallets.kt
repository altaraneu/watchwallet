package com.altaran.watchwallet.core.data

import com.altaran.watchwallet.core.classifier.AddressClassifier
import com.altaran.watchwallet.core.classifier.Classification
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.sync.SyncEngine

/**
 * Demo data for debug builds and screenshots: two people with well-known **public** wallets.
 * Never contains anything resembling key material.
 */
class DemoWallets(
    private val walletRepository: WalletRepository,
    private val syncEngine: SyncEngine,
) {
    private data class Entry(val group: String, val name: String, val input: String)

    private val entries = listOf(
        // BIP84 test vector account (mnemonic "abandon … about"): a real, public mainnet wallet.
        Entry("Alice", "Savings (xpub)", "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"),
        // vitalik.eth: public, active on many EVM networks with many (spam) tokens.
        Entry("Alice", "vitalik.eth", "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"),
        // Internet Archive Bitcoin donation address (published on archive.org).
        Entry("Bob", "Internet Archive (BTC)", "1Archive1n2C579dMsAu3iC6tWzuQJz8dN"),
        // Ethereum Foundation address (publicly known).
        Entry("Bob", "Ethereum Foundation", "0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe"),
    )

    /** Adds the demo wallets (skipping ones already tracked) and starts a sync. */
    suspend fun seed(): Int {
        var added = 0
        entries.forEach { entry ->
            val target = (AddressClassifier.classify(entry.input) as? Classification.Candidates)
                ?.candidates?.firstOrNull()?.target ?: return@forEach
            if (walletRepository.findExisting(target) != null) return@forEach
            val groupId = walletRepository.findGroup(entry.group) ?: walletRepository.createGroup(entry.group)
            walletRepository.addWallet(target, entry.name, emptyList(), groupId)
            added++
        }
        syncEngine.syncAll()
        return added
    }
}
