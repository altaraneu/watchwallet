package com.altaran.watchwallet.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

// TODO(iOS): SOCKS proxy via NSURLSessionConfiguration.connectionProxyDictionary.
actual fun createPlatformEngine(proxy: ProxyConfig?): HttpClientEngine = Darwin.create()
