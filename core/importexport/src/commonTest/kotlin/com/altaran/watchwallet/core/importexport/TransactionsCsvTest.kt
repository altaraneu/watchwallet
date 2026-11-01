package com.altaran.watchwallet.core.importexport

import kotlin.test.Test
import kotlin.test.assertEquals

class TransactionsCsvTest {
    private fun row(date: String, amount: String, wallet: String = "Savings") =
        TransactionRow(date, wallet, null, "bitcoin", "BTC", amount, "0.0000021", "BTC", "out", true, "h$date")

    @Test
    fun rowsAreNewestFirstWithEscapedCells() {
        val csv = ExportFormat.transactionsCsv(
            listOf(row("2024-01-01T00:00:00Z", "0.5"), row("2024-02-01T00:00:00Z", "-0.1", wallet = "Cold, \"main\"")),
        )
        val lines = csv.trimEnd().lines()
        assertEquals("date_utc,wallet,group,chain,asset,amount,fee,fee_asset,direction,confirmed,tx_hash", lines[0].trim())
        assertEquals("2024-02-01T00:00:00Z,\"Cold, \"\"main\"\"\",,bitcoin,BTC,-0.1,0.0000021,BTC,out,true,h2024-02-01T00:00:00Z", lines[1].trim())
        assertEquals(3, lines.size)
    }
}
