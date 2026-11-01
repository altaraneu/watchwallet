package com.altaran.watchwallet.feature.importexport

import androidx.compose.runtime.Composable

/**
 * Platform document actions. On Android: the Storage Access Framework (no storage permission)
 * and the share sheet. Exports are never written to shared storage silently.
 */
interface DocumentActions {
    /** Lets the user pick a text file; `null` when cancelled or unreadable. */
    fun openText(onResult: (String?) -> Unit)

    /** Lets the user choose where to save [content]; reports success. */
    fun saveText(fileName: String, mimeType: String, content: String, onResult: (Boolean) -> Unit)

    /** Opens the system share sheet with [content] as plain text. */
    fun shareText(content: String)
}

@Composable
internal expect fun rememberDocumentActions(): DocumentActions

/**
 * Password-based encryption of JSON exports: PBKDF2-HMAC-SHA256 (600 000 iterations, 16-byte
 * salt) derives an AES-256-GCM key; 12-byte random IV. Documented in PRIVACY.md.
 */
internal expect object ExportCrypto {
    val isSupported: Boolean

    fun encrypt(plainText: String, password: String): String

    /** `null` when the password is wrong or the file is corrupted. */
    fun decrypt(envelope: String, password: String): String?

    fun isEncrypted(text: String): Boolean
}
