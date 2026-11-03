package com.altaran.watchwallet.core.data.sync

import com.altaran.watchwallet.core.datastore.SettingsDataSource
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/** Told after every sync of all wallets which chains failed; it makes no request of its own. */
fun interface ServerFailureLog {
    /**
     * @param failed chains where not even the native balance could be read.
     * @param connected something else was reached in this round, so a failure says something about
     * the servers of that chain and not about the device being offline.
     */
    suspend fun round(failed: Set<String>, connected: Boolean)

    companion object {
        val None = ServerFailureLog { _, _ -> }
    }
}

/**
 * Consecutive failed rounds of a chain, the basis of the hint that offers server list updates. Only
 * counted on rounds some hours apart, so that pulling to refresh during an outage does not count.
 * Everything else about the round is forgotten, so chains of deleted wallets do not stay here.
 */
internal object ServerFailureCounts {
    const val REPEATED = 3
    const val MIN_SPACING_MILLIS = 6 * 60 * 60 * 1000L
    private const val SEPARATOR = '|'
    private const val FIELDS = 3

    data class Entry(val count: Int, val countedAtMillis: Long)

    fun next(previous: Map<String, Entry>, failed: Set<String>, connected: Boolean, nowMillis: Long): Map<String, Entry> {
        if (!connected) return previous
        return failed.associateWith { chain ->
            val entry = previous[chain]
            when {
                entry == null -> Entry(1, nowMillis)
                nowMillis - entry.countedAtMillis < MIN_SPACING_MILLIS -> entry
                else -> Entry(entry.count + 1, nowMillis)
            }
        }
    }

    fun repeated(stored: Set<String>): Boolean = parse(stored).values.any { it.count >= REPEATED }

    fun parse(stored: Set<String>): Map<String, Entry> = stored.mapNotNull { line ->
        val parts = line.split(SEPARATOR)
        val count = parts.getOrNull(1)?.toIntOrNull()
        val at = parts.getOrNull(2)?.toLongOrNull()
        if (parts.size == FIELDS && count != null && at != null) parts[0] to Entry(count, at) else null
    }.toMap()

    fun format(counts: Map<String, Entry>): Set<String> =
        counts.map { (chain, entry) -> "$chain$SEPARATOR${entry.count}$SEPARATOR${entry.countedAtMillis}" }.toSet()
}

/** Keeps the counts in the settings. A server the user chose themselves is not the list's business. */
internal class SettingsServerFailureLog(
    private val settings: SettingsDataSource,
    private val ownServerChains: suspend () -> Set<String>,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ServerFailureLog {
    override suspend fun round(failed: Set<String>, connected: Boolean) {
        val previous = ServerFailureCounts.parse(settings.serverFailures.first())
        val next = ServerFailureCounts.next(previous, failed - ownServerChains(), connected, nowMillis())
        if (next != previous) settings.setServerFailures(ServerFailureCounts.format(next))
    }
}
