package com.altaran.watchwallet.feature.importexport

import com.altaran.watchwallet.core.importexport.BatchEntry
import com.altaran.watchwallet.core.importexport.EntryStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportRowsTest {
    private fun entry(status: EntryStatus) = BatchEntry(1, "value", null, null, null, status, emptyList())

    @Test
    fun anUnconfirmedSuiOrAptosEntryCannotBeImported() {
        val row = ImportRows.initial(entry(EntryStatus.NEEDS_CONFIRMATION), tracked = false)
        assertFalse(row.selected)
        assertFalse(row.mayImport)
        // Tapping the row cannot select it, and neither can a stale selected flag.
        assertFalse(ImportRows.toggle(row).selected)
        assertFalse(row.copy(selected = true).mayImport)
    }

    @Test
    fun aConfirmationSelectsOnlyThatEntry() {
        val confirmed = ImportRows.confirm(ImportRows.initial(entry(EntryStatus.NEEDS_CONFIRMATION), tracked = false))
        assertTrue(confirmed.mayImport)
        // It can be deselected again like any other row.
        assertFalse(ImportRows.toggle(confirmed).mayImport)
    }

    @Test
    fun aConfirmationDoesNothingForOtherStatuses() {
        val invalid = ImportRows.initial(entry(EntryStatus.INVALID), tracked = false)
        assertFalse(ImportRows.confirm(invalid).mayImport)
        assertFalse(ImportRows.toggle(invalid).mayImport)
    }

    @Test
    fun alreadyTrackedEntriesAreNotConfirmed() {
        val tracked = ImportRows.initial(entry(EntryStatus.NEEDS_CONFIRMATION), tracked = true)
        assertFalse(ImportRows.confirm(tracked).mayImport)
    }

    @Test
    fun validatedEntriesArePreselected() {
        assertTrue(ImportRows.initial(entry(EntryStatus.READY), tracked = false).mayImport)
        assertFalse(ImportRows.initial(entry(EntryStatus.READY), tracked = true).mayImport)
    }
}
