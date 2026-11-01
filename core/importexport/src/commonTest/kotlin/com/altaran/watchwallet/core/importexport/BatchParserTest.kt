package com.altaran.watchwallet.core.importexport

import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.security.SecretKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BatchParserTest {
    private val btc = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val eth = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    private fun entries(input: String) = assertIs<BatchResult.Entries>(BatchParser.parse(input)).entries

    @Test
    fun plainListWithMixedSeparators() {
        val result = entries("$btc\n$eth, 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa;hello")
        assertEquals(listOf(EntryStatus.READY, EntryStatus.READY, EntryStatus.READY, EntryStatus.INVALID), result.map { it.status })
    }

    @Test
    fun labelledLinesInEitherOrder() {
        val result = entries("Cold storage, $btc\n$eth, Hot wallet")
        assertEquals(listOf("Cold storage", "Hot wallet"), result.map { it.label })
    }

    @Test
    fun csvWithHeaderQuotesAndBom() {
        val csv = "﻿name;group;address;notes\r\n\"Savings; long term\";Alice;$zpub;\"He said \"\"hi\"\"\"\r\nTrading;Bob;$eth;\r\n"
        val result = entries(csv)
        assertEquals(2, result.size)
        assertEquals("Savings; long term", result[0].label)
        assertEquals("Alice", result[0].group)
        assertEquals("He said \"hi\"", result[0].notes)
        assertEquals(ScriptType.P2WPKH, (result[0].candidates.single() as WatchTarget.ExtendedPublicKey).scriptType)
        assertEquals("Bob", result[1].group)
    }

    @Test
    fun duplicatesAreMarked() {
        val result = entries("$btc\n${btc.uppercase()}")
        assertEquals(listOf(EntryStatus.READY, EntryStatus.DUPLICATE), result.map { it.status })
    }

    @Test
    fun anySecretAbortsTheWholeImport() {
        val result = BatchParser.parse("$btc\nMy key, 5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ\n$eth")
        assertEquals(BatchResult.SecretDetected(SecretKind.WIF_PRIVATE_KEY, 2), result)
        assertIs<BatchResult.SecretDetected>(
            BatchParser.parse("abandon\nabandon\nabandon\nabandon\nabandon\nabandon\nabandon\nabandon\nabandon\nabandon\nabandon\nabout"),
        )
    }

    @Test
    fun jsonExportRoundTrip() {
        val wallets = listOf(
            ExportedWallet.from(WatchTarget.Address(ChainFamily.UTXO, ChainId.BITCOIN, btc, ScriptType.P2WPKH), "Cold", "Alice", "note"),
            ExportedWallet.from(WatchTarget.ExtendedPublicKey(ChainFamily.UTXO, ChainId.BITCOIN, zpub, ScriptType.P2WPKH, 30), "Xpub", null, null),
            ExportedWallet.from(WatchTarget.Address(ChainFamily.EVM, ChainId.evm(1), eth), "Eth", "Bob", null),
        )
        val export = WatchWalletExport(exportedAt = "2026-11-01T12:00:00Z", groups = listOf(ExportedGroup("Alice"), ExportedGroup("Bob")), wallets = wallets)
        val decoded = ExportFormat.decode(ExportFormat.encode(export))
        assertEquals(export, decoded, "export → import → export is lossless")

        val imported = entries(ExportFormat.encode(export))
        assertEquals(wallets.map { it.toTarget() }, imported.map { it.candidates.single() })
        assertEquals(listOf("Alice", null, "Bob"), imported.map { it.group })
    }

    @Test
    fun csvExportEscapesAndRoundTripsThroughTheParser() {
        val wallets = listOf(ExportedWallet.from(WatchTarget.Address(ChainFamily.EVM, ChainId.evm(1), eth), "Name, with comma", "G", null))
        val csv = ExportFormat.toCsv(wallets)
        val parsed = entries(csv)
        assertEquals("Name, with comma", parsed.single().label)
        assertEquals("G", parsed.single().group)
    }

    @Test
    fun otherChainRowsUseTheProvidedParser() {
        val solana = WatchTarget.Address(ChainFamily.SOLANA, ChainId("solana"), "7xYnUWgKrcPrahXHX7YMWmYXYX4xmzWrCypzF1fg7JJj")
        val otherChains: (String) -> List<WatchTarget> = { if (it == solana.address) listOf(solana) else emptyList() }
        val input = listOf("Trading, ${solana.address}", "not-an-address", "Savings, 1Archive1n2C579dMsAu3iC6tWzuQJz8dN")
            .joinToString(Char(10).toString())
        val entries = assertIs<BatchResult.Entries>(BatchParser.parse(input, otherChains)).entries
        assertEquals(listOf(EntryStatus.READY, EntryStatus.INVALID, EntryStatus.READY), entries.map { it.status })
        assertEquals(listOf(solana), entries.first().candidates)
        assertEquals("Trading", entries.first().label)
    }
}
