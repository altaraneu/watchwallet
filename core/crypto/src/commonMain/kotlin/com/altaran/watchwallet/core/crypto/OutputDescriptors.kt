package com.altaran.watchwallet.core.crypto

import com.altaran.watchwallet.core.model.ScriptType

/** A single-key output descriptor reduced to what a watch-only wallet needs. */
data class ParsedDescriptor(
    val scriptType: ScriptType,
    val extendedKey: ParsedExtendedKey,
)

/**
 * Parses single-key output descriptors (BIP380+): `pkh(KEY)`, `sh(wpkh(KEY))`, `wpkh(KEY)`,
 * `tr(KEY)`, where KEY is `[fingerprint/origin]xpub…` optionally followed by a derivation suffix
 * such as `/<0;1>/` + `*` or `/0/` + `*`. An optional
 * `#checksum` suffix is accepted. Multisig descriptors are not supported (yet).
 */
object OutputDescriptors {
    private val PATTERNS: List<Pair<Regex, ScriptType>> = listOf(
        Regex("""^sh\(wpkh\((.+)\)\)$""") to ScriptType.P2SH_P2WPKH,
        Regex("""^wpkh\((.+)\)$""") to ScriptType.P2WPKH,
        Regex("""^pkh\((.+)\)$""") to ScriptType.P2PKH,
        Regex("""^tr\((.+)\)$""") to ScriptType.P2TR,
    )
    private val KEY = Regex("""^(\[[0-9a-fA-F]{8}(/\d+['hH]?)*])?([1-9A-HJ-NP-Za-km-z]{100,112})(/.*)?$""")

    fun looksLikeDescriptor(value: String): Boolean = PATTERNS.any { (regex, _) -> regex.matches(value.substringBefore('#').trim()) }

    fun parse(value: String): ParsedDescriptor? {
        val body = value.substringBefore('#').trim()
        for ((regex, scriptType) in PATTERNS) {
            val inner = regex.matchEntire(body)?.groupValues?.get(1) ?: continue
            val keyText = KEY.matchEntire(inner)?.groupValues?.get(3) ?: return null
            val key = ExtendedKeys.parse(keyText) ?: return null
            return ParsedDescriptor(scriptType, key)
        }
        return null
    }
}
