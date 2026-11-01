package com.altaran.watchwallet.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetSocketAddress
import java.net.Proxy

actual val proxySupported: Boolean = true

actual fun createPlatformEngine(proxy: ProxyConfig?): HttpClientEngine = OkHttp.create {
    if (proxy != null) {
        this.proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(proxy.host, proxy.port))
    }
    config {
        // No HTTP cache on disk: responses may contain addresses and balances.
        cache(null)
        followRedirects(true)
        retryOnConnectionFailure(true)
    }
}
