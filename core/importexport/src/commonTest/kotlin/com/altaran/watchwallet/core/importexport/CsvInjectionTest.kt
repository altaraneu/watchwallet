package com.altaran.watchwallet.core.importexport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CsvInjectionTest {
    private val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

    private fun wallet(name: String, group: String? = null, notes: String? = null) =
        ExportedWallet(name = name, group = group, notes = notes, family = "UTXO", chain = "bitcoin", kind = "ADDRESS", value = address)

    private fun escapedCell(value: String) = Csv.split(Csv.escape(value), ',').single()

    @Test
    fun cellsAFormulaCouldStartWithAreNeutralised() {
        listOf("=1+1", "+cmd", "-cmd", "@SUM(A1)", "\t=1", "\r=1", " =1", "=HYPERLINK(\"http://x\",\"y\")").forEach { cell ->
            val escaped = escapedCell(cell)
            assertEquals('\'', escaped.first(), "cell <$cell> became <$escaped>")
        }
    }

    @Test
    fun plainTextAndNumbersStayAsTheyAre() {
        listOf("Savings", "BTC", "-1.5", "+1.5", "-0.00000001", "12", "", "2024-05-29T16:26:40Z", "it's", "a=b").forEach { cell ->
            assertEquals(cell, Csv.escape(cell))
        }
    }

    @Test
    fun negativeAmountsInTheHistoryStayNumbers() {
        val row = TransactionRow("2024-05-29T16:26:40Z", "=evil()", null, "bitcoin", "@TOKEN", "-1.5", "0.1", "BTC", "out", true, "abc")
        val cells = Csv.split(ExportFormat.transactionsCsv(listOf(row)).lines()[1], ',')
        assertEquals("-1.5", cells[5])
        assertEquals("'=evil()", cells[1])
        assertEquals("'@TOKEN", cells[4])
    }

    @Test
    fun unescapeUndoesEscapeForEveryValue() {
        listOf("=x", "'=x", "''=x", "'x", "x", "-x", "-1", "'-1", "+1", "@x", "\tx", "", "'", "''", " =x", "'hello").forEach { value ->
            assertEquals(value, Csv.unescapeFormula(escapedCell(value)), "value <$value>")
        }
    }

    @Test
    fun importReturnsTheOriginalText() {
        val original = wallet(name = "=HYPERLINK(\"http://x\")", group = "-cold storage", notes = "@note, with comma")
        val entry = assertIs<BatchResult.Entries>(BatchParser.parse(ExportFormat.toCsv(listOf(original)))).entries.single()
        assertEquals(original.name, entry.label)
        assertEquals(original.group, entry.group)
        assertEquals(original.notes, entry.notes)
        assertEquals(EntryStatus.READY, entry.status)
    }

    @Test
    fun aTextThatAlreadyLooksEscapedKeepsItsQuote() {
        val original = wallet(name = "'=already quoted")
        val entry = assertIs<BatchResult.Entries>(BatchParser.parse(ExportFormat.toCsv(listOf(original)))).entries.single()
        assertEquals(original.name, entry.label)
    }
}
