package com.altaran.watchwallet.core.data

import com.altaran.watchwallet.core.classifier.AddressClassifier
import com.altaran.watchwallet.core.classifier.Classification
import com.altaran.watchwallet.core.data.chain.ChainParsers
import com.altaran.watchwallet.core.data.repository.WalletRepository
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.WatchTarget

/**
 * Demo data for debug builds, screenshots and on-device testing: three people with **public**
 * wallets that were active when added. Never contains anything resembling key material.
 */
class DemoWallets(
    private val walletRepository: WalletRepository,
    private val syncEngine: SyncEngine,
) {
    /** Adds the demo wallets (skipping ones already tracked) and starts a sync. */
    suspend fun seed(): Int {
        var added = 0
        ENTRIES.forEach { entry ->
            val target = resolve(entry) ?: return@forEach
            if (walletRepository.findExisting(target) != null) return@forEach
            val groupId = walletRepository.findGroup(entry.group) ?: walletRepository.createGroup(entry.group)
            walletRepository.addWallet(target, entry.name, emptyList(), groupId)
            added++
        }
        syncEngine.syncAll()
        return added
    }

    internal data class Entry(val group: String, val name: String, val input: String, val confirmedFamily: ChainFamily? = null)

    internal companion object {
        /** The watch target of [entry], resolved the same way user input is. */
        fun resolve(entry: Entry): WatchTarget? = when (val result = AddressClassifier.classify(entry.input)) {
            is Classification.Candidates -> result.candidates.firstOrNull()?.target
            is Classification.ConfirmationRequired -> entry.confirmedFamily?.let { ChainParsers.parseConfirmed(entry.input, it) }
            is Classification.Unsupported, Classification.Unrecognized -> ChainParsers.parse(entry.input).firstOrNull()
            else -> null
        }

        val ENTRIES = listOf(
            // BIP84 test vector account (mnemonic "abandon … about"): a real, public mainnet wallet.
            Entry("Alice", "Savings (xpub)", "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"),
            // vitalik.eth: public, active on many EVM networks with many (spam) tokens.
            Entry("Alice", "vitalik.eth", "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"),
            // Internet Archive Bitcoin donation address (published on archive.org).
            Entry("Bob", "Internet Archive (BTC)", "1Archive1n2C579dMsAu3iC6tWzuQJz8dN"),
            // Ethereum Foundation address (publicly known).
            Entry("Bob", "Ethereum Foundation", "0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe"),
            // Carol: one busy public account per remaining chain (exchanges, treasuries, protocols).
            Entry("Carol", "Litecoin", "ltc1qr07zu594qf63xm7l7x6pu3a2v39m2z6hh5pp4t"),
            Entry("Carol", "Dogecoin (Robinhood)", "DH5yaieqoZN36fDVciNyRueRGvGLR3mr7L"),
            Entry("Carol", "Bitcoin Cash", "bitcoincash:qp3wjpa3tjlj042z2wv7hahsldgwhwy0rq9sywjpyy"),
            Entry("Carol", "Solana", "7xYnUWgKrcPrahXHX7YMWmYXYX4xmzWrCypzF1fg7JJj"),
            Entry("Carol", "XRP (genesis)", "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh"),
            Entry("Carol", "Tron (Tether treasury)", "TKHuVq1oKVruCGLvqVexFs6dawKv6fQgFs"),
            Entry("Carol", "TON", "EQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqB2N"),
            Entry("Carol", "Cardano", "stake1u8kvjx46xqyrtt8g7pmyaylp70wlnhq0uwzlk2g399vsz2q596pnr"),
            Entry("Carol", "Cosmos Hub", "cosmos1qqqtmslw64gykugz4knx4fnz556y0k9qjq5p89"),
            Entry("Carol", "Polkadot (treasury)", "13UVJyLnbVp9RBZYFwFGyDvVd1y27Tt8tkntv6Q7JVPhFsTB"),
            Entry("Carol", "Stellar", "GAUA7XL5K54CC2DDGP77FJ2YBHRJLT36CPZDXWPM6MP7MANOGG77PNJU"),
            Entry("Carol", "Sui", "0x8a7023eca66307b23451e00e72e7e95edd258ed267e1521f9c64834a031bdb02", ChainFamily.SUI),
            Entry("Carol", "Aptos", "0xd503b95164384a5ebbccbb5c4bdc8b4a5893d9651e9953abda8e1c22fcc1181d", ChainFamily.APTOS),
            Entry("Carol", "NEAR Foundation", "nearfoundation.near"),
            Entry("Carol", "Hedera (0.0.800)", "0.0.800"),
            Entry("Carol", "Algorand", "I3345FUQQ2GRBHFZQPLYQQX5HJMMRZMABCHRLWV6RCJYC6OO4MOLEUBEGU"),
            Entry("Carol", "Tezos", "tz1PqJAgoRXrWs2nLVYkA6B3UdCozEK394nG"),
            Entry("Carol", "Kaspa", "kaspa:qpz2vgvlxhmyhmt22h538pjzmvvd52nuut80y5zulgpvyerlskvvwm7n4uk5a"),
        )
    }
}
