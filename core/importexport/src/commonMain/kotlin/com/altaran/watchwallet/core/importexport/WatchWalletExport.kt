package com.altaran.watchwallet.core.importexport

import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.model.WatchTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The WatchWallet JSON export format. Versioned: [schemaVersion] increases with incompatible
 * changes and [ExportFormat.decode] migrates older files. Contains no key material, but it
 * does reveal every tracked address and xpub.
 */
@Serializable
data class WatchWalletExport(
    val schemaVersion: Int = ExportFormat.CURRENT_SCHEMA_VERSION,
    val app: String = "WatchWallet",
    /** ISO-8601 timestamp. */
    val exportedAt: String,
    val groups: List<ExportedGroup> = emptyList(),
    val wallets: List<ExportedWallet> = emptyList(),
)

@Serializable
data class ExportedGroup(val name: String)

/**
 * A cost basis the user entered for one transaction. [ref] encodes the transaction hash and asset
 * id as prefixed hex: a bare transaction hash looks exactly like a private key to the secret
 * guard, which must keep rejecting those everywhere else in the file.
 */
@Serializable
data class ExportedCost(val ref: String, val valueUsd: Double) {
    /** Transaction hash and asset id, or `null` when this entry is malformed. */
    fun decoded(): Pair<String, String>? {
        if (!ref.startsWith(REF_PREFIX) || !valueUsd.isFinite() || valueUsd < 0) return null
        val hex = ref.removePrefix(REF_PREFIX)
        if (hex.length % 2 != 0) return null
        val bytes = hex.chunked(2).map { (it.toIntOrNull(HEX_RADIX) ?: return null).toByte() }.toByteArray()
        val text = bytes.decodeToString()
        val separator = text.indexOf(SEPARATOR)
        if (separator <= 0 || separator == text.lastIndex) return null
        val hash = text.substring(0, separator)
        return if (TX_HASH.matches(hash)) hash to text.substring(separator + 1) else null
    }

    companion object {
        private const val REF_PREFIX = "cost-"
        private const val SEPARATOR = '|'
        private const val HEX_RADIX = 16
        private val TX_HASH = Regex("""^[A-Za-z0-9_=+/-]{8,128}$""")

        fun of(hash: String, assetId: String, valueUsd: Double): ExportedCost {
            val hex = "$hash$SEPARATOR$assetId".encodeToByteArray()
                .joinToString("") { (it.toInt() and 0xFF).toString(HEX_RADIX).padStart(2, '0') }
            return ExportedCost(REF_PREFIX + hex, valueUsd)
        }
    }
}

@Serializable
data class ExportedWallet(
    val name: String,
    val group: String? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val family: String,
    val chain: String,
    /** `ADDRESS` or `XPUB`. */
    val kind: String,
    val value: String,
    val scriptType: String? = null,
    val gapLimit: Int? = null,
    /** Cost basis entered by the user (automatic values are simply recalculated). */
    val costs: List<ExportedCost> = emptyList(),
) {
    fun toTarget(): WatchTarget? {
        val family = ChainFamily.entries.firstOrNull { it.name == family } ?: return null
        val script = scriptType?.let { s -> ScriptType.entries.firstOrNull { it.name == s } }
        return when (kind) {
            "XPUB" -> WatchTarget.ExtendedPublicKey(family, ChainId(chain), value, script ?: return null, gapLimit ?: WatchTarget.DEFAULT_GAP_LIMIT)
            "ADDRESS" -> WatchTarget.Address(family, ChainId(chain), value, script)
            else -> null
        }
    }

    companion object {
        fun from(target: WatchTarget, name: String, group: String?, notes: String?): ExportedWallet = when (target) {
            is WatchTarget.Address -> ExportedWallet(
                name = name,
                group = group,
                notes = notes,
                family = target.family.name,
                chain = target.chainId.value,
                kind = "ADDRESS",
                value = target.address,
                scriptType = target.scriptType?.name,
            )

            is WatchTarget.ExtendedPublicKey -> ExportedWallet(
                name = name,
                group = group,
                notes = notes,
                family = target.family.name,
                chain = target.chainId.value,
                kind = "XPUB",
                value = target.key,
                scriptType = target.scriptType.name,
                gapLimit = target.gapLimit,
            )
        }
    }
}

/** One line of the transaction history export, already formatted. */
data class TransactionRow(
    /** ISO 8601 instant in UTC, e.g. `2024-05-29T16:26:40Z`. */
    val dateUtc: String,
    val wallet: String,
    val group: String?,
    val chain: String,
    val asset: String,
    val amount: String,
    val fee: String?,
    val feeAsset: String?,
    /** `in`, `out` or `self`. */
    val direction: String,
    val confirmed: Boolean,
    val hash: String,
)

object ExportFormat {
    const val CURRENT_SCHEMA_VERSION = 1

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(export: WatchWalletExport): String = json.encodeToString(WatchWalletExport.serializer(), export)

    /** Decodes and migrates an export; `null` if the text is not a WatchWallet export. */
    fun decode(text: String): WatchWalletExport? {
        val parsed = runCatching { json.decodeFromString(WatchWalletExport.serializer(), text) }.getOrNull() ?: return null
        if (parsed.app != "WatchWallet") return null
        // Future schema versions add migrations here (parsed.schemaVersion < CURRENT_SCHEMA_VERSION).
        return parsed.takeIf { it.schemaVersion <= CURRENT_SCHEMA_VERSION }
    }

    fun looksLikeExport(text: String): Boolean = text.trimStart().startsWith("{") && "\"schemaVersion\"" in text

    /**
     * Transaction history CSV: one row per transaction and asset, newest first. Amounts are
     * signed decimal strings in the asset's unit (outgoing amounts include the fee).
     */
    fun transactionsCsv(rows: List<TransactionRow>): String {
        val header = listOf("date_utc", "wallet", "group", "chain", "asset", "amount", "fee", "fee_asset", "direction", "confirmed", "tx_hash")
        val lines = rows.sortedByDescending { it.dateUtc }.map { r ->
            listOf(
                r.dateUtc,
                r.wallet,
                r.group.orEmpty(),
                r.chain,
                r.asset,
                r.amount,
                r.fee.orEmpty(),
                r.feeAsset.orEmpty(),
                r.direction,
                r.confirmed.toString(),
                r.hash,
            )
        }
        return (listOf(header) + lines).joinToString("\r\n") { row -> row.joinToString(",") { Csv.escape(it) } } + "\r\n"
    }

    /** CSV export: one row per wallet, optional current value columns. */
    fun toCsv(wallets: List<ExportedWallet>, values: Map<String, String> = emptyMap(), valueHeader: String? = null): String {
        val header = listOf("label", "chain", "type", "address_or_xpub", "group", "tags", "notes") + listOfNotNull(valueHeader)
        val rows = wallets.map { w ->
            listOf(
                w.name,
                w.chain,
                if (w.kind ==
                    "XPUB"
                ) {
                    "xpub:${w.scriptType}"
                } else {
                    "address"
                },
                w.value,
                w.group.orEmpty(),
                w.tags.joinToString(";"),
                w.notes.orEmpty(),
            ) +
                if (valueHeader != null) listOf(values[w.value].orEmpty()) else emptyList()
        }
        return (listOf(header) + rows).joinToString("\r\n") { row -> row.joinToString(",") { Csv.escape(it) } } + "\r\n"
    }

    /** Plain text export: addresses and keys only, one per line. */
    fun toText(wallets: List<ExportedWallet>): String = wallets.joinToString("\n") { it.value } + "\n"
}
