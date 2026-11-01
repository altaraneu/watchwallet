package com.altaran.watchwallet.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

// TODO(iOS): SOCKS proxy via NSURLSessionConfiguration.connectionProxyDictionary; until then the
// proxy is reported as unsupported and the settings screen hides the option.
actual fun createPlatformEngine(proxy: ProxyConfig?): HttpClientEngine = Darwin.create()

actual val proxySupported: Boolean = false
