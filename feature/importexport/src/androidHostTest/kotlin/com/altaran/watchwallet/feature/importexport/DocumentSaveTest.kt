package com.altaran.watchwallet.feature.importexport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class DocumentSaveTest {
    private class FakeTarget(private val writeResult: Boolean = true, private val failure: Exception? = null) : SaveTarget {
        var written: ByteArray? = null
        var writes = 0
        var deleteChecks = 0

        override fun write(bytes: ByteArray): Boolean {
            writes++
            failure?.let { throw it }
            written = bytes
            return writeResult
        }

        override fun deleteIfEmpty() {
            deleteChecks++
        }
    }

    @Test
    fun aSuccessfulWriteIsReportedAsSaved() {
        val target = FakeTarget()
        assertEquals(SaveOutcome.SAVED, finishSave(target, "backup"))
        assertArrayEquals("backup".encodeToByteArray(), target.written)
        assertEquals(0, target.deleteChecks)
    }

    /** Opening the file in "wt" mode would already erase it, so that must not even be attempted. */
    @Test
    fun withoutContentNothingIsWrittenAndTheCreatedFileIsRemoved() {
        val target = FakeTarget()
        assertEquals(SaveOutcome.FAILED, finishSave(target, null))
        assertEquals(0, target.writes)
        assertEquals(1, target.deleteChecks)
    }

    @Test
    fun aFailedRemovalIsStillReportedAsAFailedSave() {
        val target = object : SaveTarget {
            override fun write(bytes: ByteArray) = true

            override fun deleteIfEmpty() = throw IOException("provider refused")
        }
        assertEquals(SaveOutcome.FAILED, finishSave(target, null))
    }

    @Test
    fun aFailedWriteIsNeverReportedAsSaved() {
        assertEquals(SaveOutcome.FAILED, finishSave(FakeTarget(writeResult = false), "backup"))
        assertEquals(SaveOutcome.FAILED, finishSave(FakeTarget(failure = IOException("disk full")), "backup"))
    }

    @Test
    fun cancellingThePickerIsNotAFailure() {
        assertEquals(SaveOutcome.CANCELLED, finishSave(null, "backup"))
        assertEquals(SaveOutcome.CANCELLED, finishSave(null, null))
    }

    @Test
    fun anEmptyExportIsWrittenAsSuch() {
        val target = FakeTarget()
        assertEquals(SaveOutcome.SAVED, finishSave(target, ""))
        assertEquals(1, target.writes)
    }

    @Test
    fun aFileWithinTheLimitIsReadCompletely() {
        val bytes = ByteArray(20_000) { it.toByte() }
        assertArrayEquals(bytes, readBounded(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test
    fun readingStopsSoonAfterTheLimit() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                served++
                return 1
            }
        }
        assertNull(readBounded(endless, 10_000))
        assertTrue("read $served bytes", served <= 10_000 + 8 * 1024)
    }

    @Test
    fun anExportIsSharedAsTextOnlyWhileItFitsAnIntent() {
        assertFalse(sharesAsFile("x".repeat(MAX_SHARE_TEXT_CHARS)))
        assertTrue(sharesAsFile("x".repeat(MAX_SHARE_TEXT_CHARS + 1)))
    }
}
