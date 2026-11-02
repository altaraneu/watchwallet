package com.altaran.watchwallet.core.importexport

import com.altaran.watchwallet.core.classifier.AddressClassifier
import com.altaran.watchwallet.core.classifier.Classification
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.security.SecretCheck
import com.altaran.watchwallet.core.security.SecretGuard
import com.altaran.watchwallet.core.security.SecretKind

/**
 * [NEEDS_CONFIRMATION]: a Sui or Aptos address from a backup. Identical to a private key in form, so
 * it needs a confirmation of its own before anything is saved or sent.
 */
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
    /** Cost basis entered by the user (JSON backups only). */
    val costs: List<ExportedCost> = emptyList(),
    /** Value of the wallet this address is subtracted from (JSON backups only). */
    val subtractFrom: String? = null,
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
     * [validate] is the chain validation of manual entry, applied to every target of a backup; what
     * it returns is what gets imported, and with `null` the entry is invalid.
     */
    fun parse(
        input: String,
        otherChains: (String) -> List<WatchTarget> = { emptyList() },
        validate: (WatchTarget) -> WatchTarget? = { target -> validateByClassifier(target, otherChains) },
    ): BatchResult {
        // Excel and other tools prepend a byte order mark to UTF-8 files.
        val text = input.removePrefix(BYTE_ORDER_MARK.toString()).trim()
        if (text.isEmpty()) return BatchResult.Empty

        findSecret(text)?.let { return it }
        if (ExportFormat.looksLikeExport(text)) {
            val export = ExportFormat.decode(text) ?: return BatchResult.Entries(emptyList())
            return fromCheckedExport(export, validate)
        }

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
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

    /**
     * The secret check alone, also for every change of the import field. A plain list or CSV has no
     * route for `0x` + 64 hex: only a backup can name the chain of such a value.
     */
    fun findSecret(input: String): BatchResult.SecretDetected? {
        val text = input.removePrefix(BYTE_ORDER_MARK.toString()).trim()
        if (text.isEmpty()) return null
        if (ExportFormat.looksLikeExport(text)) {
            val export = ExportFormat.decode(text)
            return if (export != null) secretInExport(export, text) else secretInWhole(text)
        }
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) return null
        val delimiter = Csv.detectDelimiter(lines.first())
        // Row by row first (reports the line), then every single cell (a seed phrase in a notes
        // column is diluted by the other cells of its row), then the whole input (mnemonics split
        // over lines).
        lines.forEachIndexed { i, line ->
            val cells = listOf(line) + Csv.split(line, delimiter) + line.split(',', ';', '	')
            secretIn(cells)?.let { return BatchResult.SecretDetected(it, i + 1) }
        }
        return secretInWhole(text)
    }

    private fun secretInWhole(text: String): BatchResult.SecretDetected? =
        (SecretGuard.check(text) as? SecretCheck.Secret)?.let { BatchResult.SecretDetected(it.kind, 0) }

    /** The kind of the first secret found in any of [values], checked one by one. */
    private fun secretIn(values: Iterable<String?>): SecretKind? = values.firstNotNullOfOrNull { value ->
        value?.let { (SecretGuard.check(it) as? SecretCheck.Secret)?.kind }
    }

    /**
     * Field names of a backup read like words, so word lists are searched in the values only. Every
     * string in the file counts, also in fields this version does not know. A value of the form
     * `0x` + 64 hex passes only as the address of a wallet that names Sui or Aptos as its chain, and
     * such an entry still waits for a confirmation in the preview.
     */
    private fun secretInExport(export: WatchWalletExport, text: String): BatchResult.SecretDetected? {
        secretIn(export.groups.map { it.name })?.let { return BatchResult.SecretDetected(it, 0) }
        val deliberate = export.wallets.filter(ExportedWallet::isDeliberateChainAddress).map { it.value }.toSet()
        // Every field on its own, before any entry is built.
        export.wallets.forEachIndexed { i, wallet ->
            val fields =
                listOf(
                    wallet.value.takeUnless { it in deliberate && wallet.isDeliberateChainAddress() },
                    wallet.name,
                    wallet.group,
                    wallet.notes,
                    wallet.family,
                    wallet.chain,
                    wallet.kind,
                    wallet.scriptType,
                    wallet.subtractFrom,
                ) +
                    wallet.tags + wallet.costs.map { it.ref }
            secretIn(fields)?.let { return BatchResult.SecretDetected(it, i + 1) }
        }
        secretIn(ExportFormat.stringValues(text).filterNot { it in deliberate })?.let { return BatchResult.SecretDetected(it, 0) }
        val rest = deliberate.fold(text) { remaining, value -> remaining.replace(value, "") }
        return (SecretGuard.checkKeyMaterial(rest) as? SecretCheck.Secret)?.let { BatchResult.SecretDetected(it.kind, 0) }
    }

    private fun fromCheckedExport(export: WatchWalletExport, validate: (WatchTarget) -> WatchTarget?): BatchResult = BatchResult.Entries(
        markDuplicates(
            export.wallets.mapIndexed { i, wallet ->
                val target = wallet.toTarget()?.let(validate)
                BatchEntry(
                    line = i + 1,
                    raw = wallet.value,
                    label = wallet.name,
                    group = wallet.group,
                    notes = wallet.notes,
                    status = when {
                        target == null -> EntryStatus.INVALID
                        wallet.isDeliberateChainAddress() -> EntryStatus.NEEDS_CONFIRMATION
                        else -> EntryStatus.READY
                    },
                    candidates = listOfNotNull(target),
                    costs = wallet.costs,
                    subtractFrom = wallet.subtractFrom,
                )
            },
        ),
    )

    /** Validation with the classifier and [otherChains] only; the app passes the full chain parsers. */
    private fun validateByClassifier(target: WatchTarget, otherChains: (String) -> List<WatchTarget>): WatchTarget? {
        val value = when (target) {
            is WatchTarget.Address -> target.address
            is WatchTarget.ExtendedPublicKey -> target.key
        }
        if (target.family == ChainFamily.SUI || target.family == ChainFamily.APTOS) return target.takeIf { SecretGuard.isHexAddressForm(value) }
        val classified = (AddressClassifier.classify(value) as? Classification.Candidates)?.candidates?.map { it.target }.orEmpty()
        return (classified + otherChains(value)).firstOrNull { parsed ->
            when {
                parsed.family != target.family -> false
                parsed is WatchTarget.ExtendedPublicKey -> target is WatchTarget.ExtendedPublicKey && parsed.scriptType == target.scriptType
                else -> target is WatchTarget.Address
            }
        }
    }

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
                fun text(column: Int?) = column?.let { cells.getOrNull(it) }?.let(Csv::unescapeFormula)?.trim()?.ifBlank { null }
                entry(
                    line = i + 2,
                    value = cells.getOrNull(addressColumn).orEmpty().trim(),
                    label = text(label),
                    group = text(group),
                    notes = text(notes),
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
            is Classification.Candidates, is Classification.Unsupported, Classification.WrongChecksum -> true
            else -> otherChains(value).isNotEmpty()
        }

        private fun entry(line: Int, value: String, label: String?, group: String?, notes: String?): BatchEntry {
            val base = BatchEntry(line, value, label, group, notes, EntryStatus.INVALID, emptyList())
            return when (val result = AddressClassifier.classify(value)) {
                is Classification.Candidates -> {
                    val targets = result.candidates.map { it.target }
                    base.copy(status = if (targets.size == 1) EntryStatus.READY else EntryStatus.AMBIGUOUS, candidates = targets)
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

    private const val TEXT_MARK = '\''
    private const val FORMULA_STARTS = "=+-@\t\r\n"
    private val PLAIN_NUMBER = Regex("""^[+-]?[0-9]+(\.[0-9]+)?$""")

    /**
     * Whether a spreadsheet could run [value] as a formula (it starts with one of [FORMULA_STARTS],
     * also behind spaces or text marks). Token names and symbols come from servers, so they are
     * untrusted. A plain number stays a number, so negative amounts remain usable.
     */
    private fun looksLikeFormula(value: String): Boolean {
        val body = value.trimStart(TEXT_MARK)
        val first = body.firstOrNull { it in FORMULA_STARTS || !it.isWhitespace() } ?: return false
        return first in FORMULA_STARTS && !PLAIN_NUMBER.matches(body.trim())
    }

    /** Quotes [value] for a CSV file and marks formula-like text as text with a leading `'`. */
    fun escape(value: String): String {
        val safe = if (looksLikeFormula(value)) TEXT_MARK + value else value
        return if (safe.any { it == ',' || it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    /** Undoes the mark of [escape], and only that: any other leading `'` belongs to the value. */
    fun unescapeFormula(value: String): String =
        if (value.firstOrNull() == TEXT_MARK && looksLikeFormula(value.drop(1))) value.drop(1) else value
}
