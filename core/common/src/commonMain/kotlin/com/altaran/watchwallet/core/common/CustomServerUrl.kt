package com.altaran.watchwallet.core.common

/**
 * Rules for a server the user enters. Encryption is required except where the traffic stays on the
 * device or the local network, or Tor already encrypts it: `.onion`, localhost, private addresses.
 */
object CustomServerUrl {
    private val URL = Regex("""^(https?)://([^/?#\s]+)(/[^?#\s]*)?$""", RegexOption.IGNORE_CASE)
    private val HOST_NAME = Regex("""^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$""")
    private val IPV6 = Regex("""^\[([0-9a-f:.]+)]$""")
    private val PORT = Regex("""^\d{1,5}$""")
    private const val MAX_PORT = 65535
    private val LOCAL_SUFFIXES = listOf(".onion", ".local", ".localhost")

    /** The URL without trailing slash when it may be used, otherwise `null`. Never echoes it anywhere. */
    fun validate(raw: String): String? {
        val text = raw.trim().trimEnd('/')
        val match = URL.matchEntire(text) ?: return null
        val authority = match.groupValues[2].lowercase()
        val host = hostOf(authority) ?: return null
        val secure = match.groupValues[1].lowercase() == "https"
        return text.takeIf { secure || isCleartextAllowedHost(host) }
    }

    /**
     * A stored value the rules no longer allow. The app does not use it, so Bitcoin addresses go to
     * the default servers, and the user must be told.
     */
    fun isIgnored(stored: String?): Boolean = stored != null && validate(stored) == null

    /** `null` for user info (`user@host` can disguise the real host) and malformed hosts or ports. */
    private fun hostOf(authority: String): String? {
        if ('@' in authority) return null
        val bracket = authority.indexOf(']')
        val host: String
        val port: String?
        if (authority.startsWith("[") && bracket > 0) {
            host = authority.substring(0, bracket + 1)
            port = authority.substring(bracket + 1).takeIf { it.isNotEmpty() }?.removePrefix(":")
            if (!IPV6.matches(host)) return null
        } else {
            host = authority.substringBefore(':')
            port = authority.substringAfter(':', "").takeIf { ':' in authority }
            if (!HOST_NAME.matches(host)) return null
        }
        val portValid = port == null || (PORT.matches(port) && port.toInt() in 1..MAX_PORT)
        return host.takeIf { portValid }
    }

    fun isCleartextAllowedHost(rawHost: String): Boolean {
        val host = rawHost.lowercase().removeSurrounding("[", "]")
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) && host.length > it.length }) return true
        return if (':' in host) isPrivateIpv6(host) else isPrivateIpv4(host)
    }

    /** The private ranges of RFC 1918, plus loopback and link-local. */
    @Suppress("MagicNumber")
    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != IPV4_PARTS || parts.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return false
        val (a, b) = parts.map { it.toInt() }.also { octets -> if (octets.any { it > MAX_OCTET }) return false }
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
    }

    private fun isPrivateIpv6(host: String): Boolean = host == "::1" ||
        host.startsWith("fc") || host.startsWith("fd") ||
        host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")

    private const val IPV4_PARTS = 4
    private const val MAX_OCTET = 255
}
