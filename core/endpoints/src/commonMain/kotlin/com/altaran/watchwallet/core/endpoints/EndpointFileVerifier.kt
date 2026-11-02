package com.altaran.watchwallet.core.endpoints

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.byteVector
import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.io.encoding.Base64

/**
 * A verified remote server list. [entries] holds only what this build can use.
 *
 * @param signedAtSeconds when it was signed; files from before this field existed have none.
 * @param signedByRecoveryKey see [EndpointFileVerifier.RECOVERY_PUBLIC_KEY].
 */
data class EndpointFile(
    val version: Long,
    val expiresAtSeconds: Long,
    val entries: List<EndpointEntry>,
    val signedAtSeconds: Long? = null,
    val signedByRecoveryKey: Boolean = false,
) {
    fun isExpired(nowSeconds: Long): Boolean = nowSeconds >= expiresAtSeconds

    /** Valid for longer than any list may be, so a stolen key could not outlast a release cycle. */
    fun expiresTooLate(nowSeconds: Long): Boolean = expiresAtSeconds - nowSeconds > MAX_VALIDITY_SECONDS

    fun isUsable(nowSeconds: Long): Boolean = !isExpired(nowSeconds) && !expiresTooLate(nowSeconds)

    companion object {
        /** `expires_in_days` in the signing tool stays below this; a test keeps the two equal. */
        const val MAX_VALIDITY_SECONDS = 200 * 24 * 60 * 60L
    }
}

enum class EndpointRejection { OVERSIZE, MALFORMED, BAD_SIGNATURE, BAD_PAYLOAD, EXPIRED, EXPIRES_TOO_LATE, STALE_VERSION }

sealed interface EndpointFileResult {
    data class Valid(val file: EndpointFile) : EndpointFileResult

    data class Rejected(val reason: EndpointRejection) : EndpointFileResult
}

/**
 * Opens the signed envelope `{"payload":"<base64>","signature":"<hex>"}`. The signature is BIP-340
 * Schnorr over SHA-256 of the decoded payload bytes, so no JSON canonicalization is involved, and
 * nothing in the payload is parsed before the signature has been checked.
 *
 * Entries are data only: a known chain, a known API type and https URLs. An entry that does not
 * qualify is dropped on its own; the rest of the file still applies.
 *
 * @param publicKeyHex x-only public key; only tests pass anything but [PINNED_PUBLIC_KEY].
 * @param recoveryKeyHex x-only public key of the offline recovery key, if this build pins one.
 * @param minVersion files below this version are refused, whoever signed them.
 */
class EndpointFileVerifier(
    publicKeyHex: String = PINNED_PUBLIC_KEY,
    recoveryKeyHex: String? = RECOVERY_PUBLIC_KEY,
    private val minVersion: Long = MIN_ACCEPTED_VERSION,
) {
    private val publicKey = publicKeyHex.hexToByteArray()
    private val recoveryKey = recoveryKeyHex?.hexToByteArray()

    /** Never throws: whatever arrives from the network or storage ends in a [EndpointFileResult]. */
    fun open(envelope: String): EndpointFileResult {
        if (envelope.length > MAX_ENVELOPE_BYTES) return EndpointFileResult.Rejected(EndpointRejection.OVERSIZE)
        val decoded = runCatching {
            val dto = json.decodeFromString<EnvelopeDto>(envelope)
            Base64.decode(dto.payload) to dto.signature.hexToByteArray()
        }.getOrNull() ?: return EndpointFileResult.Rejected(EndpointRejection.MALFORMED)
        val (payload, signature) = decoded
        val byRecoveryKey = when {
            verifySignature(payload, signature, publicKey) -> false
            recoveryKey != null && verifySignature(payload, signature, recoveryKey) -> true
            else -> return EndpointFileResult.Rejected(EndpointRejection.BAD_SIGNATURE)
        }
        val file = runCatching { parsePayload(payload, byRecoveryKey) }.getOrNull()
            ?: return EndpointFileResult.Rejected(EndpointRejection.BAD_PAYLOAD)
        if (file.version < minVersion) return EndpointFileResult.Rejected(EndpointRejection.STALE_VERSION)
        return EndpointFileResult.Valid(file)
    }

    private fun verifySignature(payload: ByteArray, signature: ByteArray, key: ByteArray): Boolean {
        if (signature.size != SIGNATURE_BYTES || key.size != PUBLIC_KEY_BYTES) return false
        // A native library that fails to load must not break the app: the bundled list still works.
        return runCatching {
            Secp256k1.verifySchnorr(signature, Crypto.sha256(payload.byteVector()), key)
        }.getOrDefault(false)
    }

    private fun parsePayload(payload: ByteArray, byRecoveryKey: Boolean): EndpointFile? {
        val dto = json.decodeFromString<PayloadDto>(payload.decodeToString(throwOnInvalidSequence = true))
        if (dto.format != PAYLOAD_FORMAT || dto.version < 1 || dto.expires < 1) return null
        return EndpointFile(dto.version, dto.expires, dto.endpoints.mapNotNull(::parseEntry), dto.signed, byRecoveryKey)
    }

    private fun parseEntry(element: JsonElement): EndpointEntry? {
        val dto = runCatching { json.decodeFromJsonElement(EntryDto.serializer(), element) }.getOrNull() ?: return null
        if (!BundledEndpoints.accepts(dto.chain, dto.protocol)) return null
        val urls = dto.urls.take(MAX_URLS_PER_ENTRY).mapNotNull(::cleanUrl).distinct()
        return if (urls.isEmpty()) null else EndpointEntry(dto.chain, dto.protocol, urls)
    }

    companion object {
        /** The owner's signing key, see `docs/endpoints-config.md` for rotation. */
        const val PINNED_PUBLIC_KEY = "d518716d260dec9a323611396e9917f219a8ef02824b877975638af91e0f8866"

        /**
         * Offline key that can take over if [PINNED_PUBLIC_KEY] is stolen: a file it signed beats
         * any file of the normal key, whatever the version, and ends further updates by that key.
         * Not set yet: the project owner has to generate one and enter it here before a release.
         * See "Recovery key" in `docs/endpoints-config.md`.
         */
        val RECOVERY_PUBLIC_KEY: String? = null

        /**
         * Lowest version this build accepts. Raise it in a release to drop a list known to be bad,
         * including a file with a stolen key's version or a recovery file that has done its job.
         */
        const val MIN_ACCEPTED_VERSION = 1L

        /** Limit for the whole downloaded file; the real list is a few kilobytes. */
        const val MAX_ENVELOPE_BYTES = 256 * 1024

        private const val PAYLOAD_FORMAT = 1
        private const val SIGNATURE_BYTES = 64
        private const val PUBLIC_KEY_BYTES = 32
        private const val MAX_URLS_PER_ENTRY = 8
        private const val MAX_URL_LENGTH = 512

        /** Plain https: no credentials, query or fragment, as no adapter needs them. */
        private val URL_PATTERN = Regex("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/%-]*)?")

        private val json = Json { ignoreUnknownKeys = true }

        private fun cleanUrl(url: String): String? =
            url.takeIf { it.length <= MAX_URL_LENGTH && URL_PATTERN.matches(it) }?.trimEnd('/')
    }
}

@Serializable
private data class EnvelopeDto(val payload: String, val signature: String)

@Serializable
private data class PayloadDto(
    val format: Int,
    val version: Long,
    val expires: Long,
    val signed: Long? = null,
    val endpoints: List<JsonElement>,
)

@Serializable
private data class EntryDto(
    val chain: String,
    val protocol: String,
    val urls: List<String>,
)
