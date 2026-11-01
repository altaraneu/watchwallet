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
