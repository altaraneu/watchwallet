package com.altaran.watchwallet.core.endpoints

/** Remembers which hosts the user was last told about; implemented over the settings DataStore. */
interface EndpointHostsStore {
    /** `null` before the first app start with this feature. */
    suspend fun seenHosts(): Set<String>?

    suspend fun saveSeenHosts(hosts: Set<String>)
}

data class EndpointHostChange(val added: List<String>, val removed: List<String>)

/**
 * Tells the user once when the servers that receive their addresses changed, whether through the
 * signed list, an app update or the settings. A server the user entered themselves does not count.
 *
 * A change stays pending until [acknowledge], so a notice nobody saw is not lost. The first start
 * only records the current hosts: nothing has changed for a new user.
 */
class EndpointHostNotice(private val catalog: EndpointCatalog, private val store: EndpointHostsStore) {
    suspend fun pending(): EndpointHostChange? {
        val seen = store.seenHosts()
        if (seen == null) {
            store.saveSeenHosts(catalog.listHosts)
            return null
        }
        val current = catalog.listHosts
        return if (seen == current) null else EndpointHostChange((current - seen).sorted(), (seen - current).sorted())
    }

    suspend fun acknowledge() = store.saveSeenHosts(catalog.listHosts)
}
