package com.altaran.watchwallet.core.importexport

import com.altaran.watchwallet.core.classifier.AddressClassifier
import com.altaran.watchwallet.core.classifier.Classification
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.security.SecretCheck
import com.altaran.watchwallet.core.security.SecretGuard
import com.altaran.watchwallet.core.security.SecretKind

enum class EntryStatus { READY, AMBIGUOUS, DUPLICATE, NEEDS_CONFIRMATION, UNSUPPORTED, INVALID }

/** One row of a batch import preview. */
data class BatchEntry(
    val line: Int,
    val raw: String,
    val label: String?,
    val group: String?,
    val notes: String?,
    val status: EntryStatus,
    /** Possible targets; one for READY, several for AMBIGUOUS (bare xpub script types). */
    val candidates: List<WatchTarget>,
    val unsupportedFamily: ChainFamily? = null,
)

sealed interface BatchResult {
    /** Key material found somewhere in the input: the whole import is aborted and discarded. */
    data class SecretDetected(val kind: SecretKind, val line: Int) : BatchResult

    data class Entries(val entries: List<BatchEntry>) : BatchResult

    data object Empty : BatchResult
}

/**
 * Parses batch input: WatchWallet JSON, CSV with a header row, `label, address` /
 * `address, label` lines, or plain lists separated by newlines, commas, semicolons or spaces.
 *
 * The secret guard runs over the whole input and every row **before** anything else.
 */
private val BYTE_ORDER_MARK = Char(0xFEFF)

object BatchParser {
    private val ADDRESS_HEADERS = setOf("address", "addresses", "xpub", "address_or_xpub", "key", "public_key", "descriptor")
    private val LABEL_HEADERS = setOf("label", "name", "wallet", "title")
    private val GROUP_HEADERS = setOf("group", "portfolio", "owner", "folder")
    private val NOTES_HEADERS = setOf("notes", "note", "comment", "description")
    private val CHAIN_HEADERS = setOf("chain", "network", "blockchain", "coin")

    /**
     * [otherChains] recognises formats of chains beyond Bitcoin and EVM (provided by the data
     * layer, which knows the registered chain adapters); it only sees rows that passed the guard.
     */
    fun parse(input: String, otherChains: (String) -> List<WatchTarget> = { emptyList() }): BatchResult {
        // Excel and other tools prepend a byte order mark to UTF-8 files.
        val text = input.removePrefix(BYTE_ORDER_MARK.toString()).trim()
        if (text.isEmpty()) return BatchResult.Empty

        if (ExportFormat.looksLikeExport(text)) {
            (SecretGuard.check(text) as? SecretCheck.Secret)?.let { return BatchResult.SecretDetected(it.kind, 0) }
            val export = ExportFormat.decode(text) ?: return BatchResult.Entries(emptyList())
            return fromExport(export)
        }

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        // Row by row first (reports the line), then the whole input (mnemonics split over lines).
        lines.forEachIndexed { i, line ->
            (SecretGuard.check(line) as? SecretCheck.Secret)?.let { return BatchResult.SecretDetected(it.kind, i + 1) }
        }
        (SecretGuard.check(text) as? SecretCheck.Secret)?.let { return BatchResult.SecretDetected(it.kind, 0) }

        val delimiter = Csv.detectDelimiter(lines.first())
        val header = Csv.split(lines.first(), delimiter).map { it.trim().lowercase() }
        val addressColumn = header.indexOfFirst { it in ADDRESS_HEADERS }
        val parser = RowParser(otherChains)
        val rows = if (addressColumn >= 0) {
            parser.fromCsv(lines.drop(1), delimiter, header, addressColumn)
        } else {
            parser.fromLines(lines)
        }
        return BatchResult.Entries(markDuplicates(rows))
    }

    private fun fromExport(export: WatchWalletExport): BatchResult = BatchResult.Entries(
        markDuplicates(
            export.wallets.mapIndexed { i, wallet ->
                val target = wallet.toTarget()
                if (SecretGuard.isSecret(wallet.value)) return BatchResult.SecretDetected(SecretKind.HEX_PRIVATE_KEY, i + 1)
                BatchEntry(
                    line = i + 1,
                    raw = wallet.value,
                    label = wallet.name,
                    group = wallet.group,
                    notes = wallet.notes,
                    status = if (target != null) EntryStatus.READY else EntryStatus.INVALID,
                    candidates = listOfNotNull(target),
                )
            },
        ),
    )

    /** Row classification for one parse call. */
    private class RowParser(private val otherChains: (String) -> List<WatchTarget>) {
        fun fromCsv(rows: List<String>, delimiter: Char, header: List<String>, addressColumn: Int): List<BatchEntry> {
            fun col(names: Set<String>) = header.indexOfFirst { it in names }.takeIf { it >= 0 }
            val label = col(LABEL_HEADERS)
            val group = col(GROUP_HEADERS)
            val notes = col(NOTES_HEADERS)

            @Suppress("UNUSED_VARIABLE")
            val chain = col(CHAIN_HEADERS) // Reserved: resolve ambiguous rows by chain.
            return rows.mapIndexed { i, row ->
                val cells = Csv.split(row, delimiter)
                entry(
                    line = i + 2,
                    value = cells.getOrNull(addressColumn).orEmpty().trim(),
                    label = label?.let { cells.getOrNull(it)?.trim() }?.ifBlank { null },
                    group = group?.let { cells.getOrNull(it)?.trim() }?.ifBlank { null },
                    notes = notes?.let { cells.getOrNull(it)?.trim() }?.ifBlank { null },
                )
            }
        }

        /**
         * `label, address`, `address, label`, or one or more bare addresses per line separated by
         * commas, semicolons, tabs or spaces. With several addresses on a line, other tokens are
         * reported as invalid rows instead of being used as labels.
         */
        fun fromLines(lines: List<String>): List<BatchEntry> = lines.flatMapIndexed { i, line ->
            val cells = line.split(',', ';', '	').map { it.trim() }.filter { it.isNotEmpty() }
            val recognized = cells.filter { isAddressLike(it) }
            when {
                recognized.isEmpty() -> {
                    // Space-separated list of addresses on one line?
                    val words = line.split(' ').filter { it.isNotBlank() }
                    if (words.size > 1 && words.any { isAddressLike(it) }) {
                        words.map { entry(i + 1, it, null, null, null) }
                    } else {
                        listOf(entry(i + 1, cells.firstOrNull().orEmpty(), null, null, null))
                    }
                }

                recognized.size == 1 -> {
                    val label = cells.filterNot { it == recognized.single() }.joinToString(" ").ifBlank { null }
                    listOf(entry(i + 1, recognized.single(), label, null, null))
                }

                else -> {
                    cells.map { entry(i + 1, it, null, null, null) }
                }
            }
        }

        private fun isAddressLike(value: String): Boolean = when (AddressClassifier.classify(value)) {
            is Classification.Candidates, is Classification.ConfirmationRequired, is Classification.Unsupported, Classification.WrongChecksum -> true
            else -> otherChains(value).isNotEmpty()
        }

        private fun entry(line: Int, value: String, label: String?, group: String?, notes: String?): BatchEntry {
            val base = BatchEntry(line, value, label, group, notes, EntryStatus.INVALID, emptyList())
            return when (val result = AddressClassifier.classify(value)) {
                is Classification.Candidates -> {
                    val targets = result.candidates.map { it.target }
                    base.copy(status = if (targets.size == 1) EntryStatus.READY else EntryStatus.AMBIGUOUS, candidates = targets)
                }

                is Classification.ConfirmationRequired -> {
                    base.copy(status = EntryStatus.NEEDS_CONFIRMATION)
                }

                is Classification.Unsupported, Classification.Unrecognized -> {
                    val targets = otherChains(value)
                    when {
                        targets.size == 1 -> base.copy(status = EntryStatus.READY, candidates = targets)
                        targets.size > 1 -> base.copy(status = EntryStatus.AMBIGUOUS, candidates = targets)
                        result is Classification.Unsupported -> base.copy(status = EntryStatus.UNSUPPORTED, unsupportedFamily = result.family)
                        else -> base
                    }
                }

                else -> {
                    base
                }
            }
        }
    }

    private fun markDuplicates(entries: List<BatchEntry>): List<BatchEntry> {
        val seen = mutableSetOf<String>()
        return entries.map { entry ->
            val key = entry.candidates.firstOrNull()?.let { keyOf(it) } ?: return@map entry
            if (!seen.add(key)) entry.copy(status = EntryStatus.DUPLICATE) else entry
        }
    }

    fun keyOf(target: WatchTarget): String = when (target) {
        is WatchTarget.Address -> "${target.family}:${target.address.lowercase()}"
        is WatchTarget.ExtendedPublicKey -> "${target.family}:${target.key}"
    }
}

/** Minimal RFC 4180 CSV helpers (quotes, escaped quotes, `,` `;` or tab delimiters). */
object Csv {
    fun detectDelimiter(firstLine: String): Char {
        val candidates = listOf(',', ';', '\t')
        return candidates.maxBy { c -> firstLine.count { it == c } }.takeIf { c -> firstLine.contains(c) } ?: ','
    }

    fun split(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> {
                    current.append('"')
                    i++
                }

                c == '"' -> {
                    quoted = !quoted
                }

                !quoted && c == delimiter -> {
                    cells += current.toString()
                    current.clear()
                }

                else -> {
                    current.append(c)
                }
            }
            i++
        }
        cells += current.toString()
        return cells
    }

    fun escape(value: String): String =
        if (value.any { it == ',' || it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
