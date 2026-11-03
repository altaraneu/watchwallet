package com.altaran.watchwallet.core.endpoints

import com.altaran.watchwallet.core.model.ChainId

/** What the app can tell about the list in force, for the user to see in the settings. */
data class EndpointListInfo(
    val version: Long,
    /** `false`: the list built into the app. */
    val remote: Boolean,
    val signedAtSeconds: Long? = null,
    val expiresAtSeconds: Long? = null,
    /** Every host that can receive addresses in this run, including the user's own server. */
    val hosts: List<String>,
)

/**
 * The servers this run of the app uses. Precedence, highest first: the user's custom server, an
 * approved remote file that is valid and not expired, the bundled defaults. Built once at startup,
 * so a newly applied file takes effect at the next app start.
 */
class EndpointCatalog private constructor(
    private val table: Map<Pair<String, String>, List<String>>,
    /** The bundled or signed list alone; a server the user entered is their own choice. */
    private val listTable: Map<Pair<String, String>, List<String>>,
    private val file: EndpointFile?,
) {
    val info: EndpointListInfo
        get() = EndpointListInfo(
            version = file?.version ?: BundledEndpoints.VERSION,
            remote = file != null,
            signedAtSeconds = file?.signedAtSeconds,
            expiresAtSeconds = file?.expiresAtSeconds,
            hosts = hostsOf(table.values.flatten()).sorted(),
        )

    /**
     * Hosts with port that serve a pair of chain and API type here but not in [current], sorted. What
     * the app itself ships for that pair does not count: it went through a release.
     */
    internal fun addedComparedTo(current: EndpointCatalog): List<String> = listTable.flatMap { (pair, urls) ->
        val known = (current.listTable[pair].orEmpty() + BUNDLED.listTable[pair].orEmpty()).map(::authorityOf).toSet()
        urls.map(::authorityOf).filter { it !in known }
    }.distinct().sorted()

    fun urls(chain: ChainId, protocol: String): List<String> = table[chain.value to protocol].orEmpty()

    fun firstOrNull(chain: ChainId, protocol: String): String? = urls(chain, protocol).firstOrNull()

    /** For API types every build ships a default for, which the overlay cannot remove. */
    fun first(chain: ChainId, protocol: String): String =
        requireNotNull(firstOrNull(chain, protocol)) { "No $protocol endpoint for ${chain.value}" }

    companion object {
        val BUNDLED: EndpointCatalog = tableOf(BundledEndpoints.ENTRIES).let { EndpointCatalog(it, it, null) }

        /** What [file] would put in force, whatever is in force now. */
        internal fun of(file: EndpointFile): EndpointCatalog =
            tableOf(BundledEndpoints.ENTRIES + file.entries).let { EndpointCatalog(it, it, file) }

        /**
         * After expiry the stored file no longer applies and the bundled defaults take over: see
         * "Expiry" in `docs/endpoints-config.md` for why that beats keeping the last valid list.
         *
         * @param stored the approved envelope; verified again here, so a file signed with a key this
         * build no longer pins stops applying.
         * @param autoUpdate `false` means bundled defaults only, whatever is stored.
         * @param customEsplora the user's own Bitcoin server. It may be plain http (for example on a
         * home network), since the user chose it; the https rule is about the remote file.
         */
        fun resolve(
            stored: String?,
            autoUpdate: Boolean,
            customEsplora: String?,
            nowSeconds: Long,
            verifier: EndpointFileVerifier = EndpointFileVerifier(),
        ): EndpointCatalog {
            val opened = if (autoUpdate && stored != null) (verifier.open(stored) as? EndpointFileResult.Valid)?.file else null
            val file = opened?.takeIf { it.isUsable(nowSeconds) }
            val table = tableOf(BundledEndpoints.ENTRIES + file?.entries.orEmpty())
            val bitcoin = ChainId.BITCOIN.value to EndpointProtocol.ESPLORA
            val final = if (customEsplora != null) table + (bitcoin to listOf(customEsplora)) else table
            return EndpointCatalog(final, table, file)
        }

        private fun tableOf(entries: List<EndpointEntry>) = entries.associate { (it.chain to it.protocol) to it.urls }

        private fun hostsOf(urls: List<String>) = urls.map { authorityOf(it).substringBefore(':') }.toSet()

        private fun authorityOf(url: String) = url.substringAfter("://").substringBefore('/').substringAfter('@').lowercase()
    }
}
