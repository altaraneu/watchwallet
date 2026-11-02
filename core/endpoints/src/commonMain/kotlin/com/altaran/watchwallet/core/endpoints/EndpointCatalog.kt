package com.altaran.watchwallet.core.endpoints

import com.altaran.watchwallet.core.model.ChainId

/**
 * The servers this run of the app uses. Precedence, highest first: the user's custom server, a
 * stored remote file that is valid and not expired, the bundled defaults. Built once at startup, so
 * a newly downloaded file takes effect at the next app start.
 */
class EndpointCatalog private constructor(private val table: Map<Pair<String, String>, List<String>>) {
    fun urls(chain: ChainId, protocol: String): List<String> = table[chain.value to protocol].orEmpty()

    fun firstOrNull(chain: ChainId, protocol: String): String? = urls(chain, protocol).firstOrNull()

    /** For API types every build ships a default for, which the overlay cannot remove. */
    fun first(chain: ChainId, protocol: String): String =
        requireNotNull(firstOrNull(chain, protocol)) { "No $protocol endpoint for ${chain.value}" }

    companion object {
        val BUNDLED: EndpointCatalog = EndpointCatalog(tableOf(BundledEndpoints.ENTRIES))

        /**
         * After expiry the stored file no longer applies and the bundled defaults take over: see
         * "Expiry" in `docs/endpoints-config.md` for why that beats keeping the last valid list.
         *
         * @param stored the stored envelope; verified again here, so a file signed with a key this
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
            val file = if (autoUpdate && stored != null) (verifier.open(stored) as? EndpointFileResult.Valid)?.file else null
            val overlay = file?.takeUnless { it.isExpired(nowSeconds) }?.entries.orEmpty()
            val table = tableOf(BundledEndpoints.ENTRIES + overlay)
            val bitcoin = ChainId.BITCOIN.value to EndpointProtocol.ESPLORA
            return EndpointCatalog(if (customEsplora != null) table + (bitcoin to listOf(customEsplora)) else table)
        }

        private fun tableOf(entries: List<EndpointEntry>) = entries.associate { (it.chain to it.protocol) to it.urls }
    }
}
