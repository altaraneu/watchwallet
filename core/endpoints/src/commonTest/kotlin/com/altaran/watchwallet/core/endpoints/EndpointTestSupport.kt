package com.altaran.watchwallet.core.endpoints

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.byteVector
import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/** Throwaway keys of the public BIP-340 test vectors; the app never trusts them. */
internal object TestKeys {
    val SECRET = "0000000000000000000000000000000000000000000000000000000000000003".hexToByteArray()
    const val PUBLIC = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
    val OTHER_SECRET = "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef".hexToByteArray()
}

internal const val NOW = 1_800_000_000L
internal const val LATER = NOW + 86_400L

internal val verifier = EndpointFileVerifier(TestKeys.PUBLIC)

internal fun entry(chain: String = "bitcoin", protocol: String = "esplora", vararg urls: String = arrayOf("https://new.test/api")) =
    EndpointEntry(chain, protocol, urls.toList())

internal fun payload(version: Long = 5, expires: Long = LATER, format: Int = 1, entries: List<EndpointEntry> = listOf(entry())): String =
    buildJsonObject {
        put("format", format)
        put("version", version)
        put("expires", expires)
        put(
            "endpoints",
            buildJsonArray {
                entries.forEach { item ->
                    add(
                        buildJsonObject {
                            put("chain", item.chain)
                            put("protocol", item.protocol)
                            put("urls", buildJsonArray { item.urls.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                        },
                    )
                }
            },
        )
    }.toString()

internal fun signature(payload: String, secret: ByteArray = TestKeys.SECRET): String {
    val digest = Crypto.sha256(payload.encodeToByteArray().byteVector())
    return Secp256k1.signSchnorr(digest, secret, ByteArray(32) { 7 }).toHexString()
}

internal fun envelope(payload: String, signature: String = signature(payload)): String =
    """{"payload":"${Base64.encode(payload.encodeToByteArray())}","signature":"$signature"}"""

internal fun signed(version: Long = 5, expires: Long = LATER, entries: List<EndpointEntry> = listOf(entry())): String =
    envelope(payload(version, expires, entries = entries))
