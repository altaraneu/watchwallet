package com.altaran.watchwallet.core.database

import androidx.room.RoomDatabase
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL

/**
 * Makes SQLite overwrite deleted content instead of only marking it as free. The setting belongs to
 * each connection, and Room's callbacks run for only one of its pool, so it is set where every
 * connection is opened. Needs no schema change.
 */
internal class SecureDeleteDriver(private val driver: SQLiteDriver) : SQLiteDriver by driver {
    override fun open(fileName: String): SQLiteConnection = driver.open(fileName).also { it.execSQL("PRAGMA secure_delete = ON") }
}

/** [scrubDeletedData] for modules that do not see Room. */
fun interface DeletedDataScrubber {
    suspend fun scrub()
}

/**
 * After a wallet was deleted: rewrites the database, which also clears pages freed before
 * `secure_delete` was on, and empties the write-ahead log, which otherwise keeps old page copies.
 * A reader in progress can make the checkpoint leave the log as it is; the next one catches up.
 */
suspend fun RoomDatabase.scrubDeletedData() {
    useWriterConnection { connection ->
        connection.usePrepared("VACUUM") { it.step() }
        connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() }
    }
}
