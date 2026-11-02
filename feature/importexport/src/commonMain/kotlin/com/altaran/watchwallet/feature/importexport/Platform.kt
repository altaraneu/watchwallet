package com.altaran.watchwallet.feature.importexport

import androidx.compose.runtime.Composable

/**
 * Platform document actions. On Android: the Storage Access Framework (no storage permission)
 * and the share sheet. Exports are never written to shared storage silently.
 */
interface DocumentActions {
    /** Lets the user pick a text file; `null` when cancelled or unreadable. */
    fun openText(onResult: (String?) -> Unit)

    /**
     * Lets the user choose where to save a file. The picker can outlive this screen, or even the
     * process, so the content is not passed here: when the user has chosen, the platform asks for
     * it through `takeSaveContent` of [rememberDocumentActions] and reports through `onSaveFinished`.
     */
    fun createDocument(fileName: String, mimeType: String)

    /** Opens the system share sheet with [content]; `false` when that was not possible. */
    fun share(fileName: String, mimeType: String, content: String): Boolean
}

/**
 * @param takeSaveContent hands over the content of the file being saved, once; `null` when it is
 * gone, for example because the app was killed while the file picker was open.
 */
@Composable
internal expect fun rememberDocumentActions(
    takeSaveContent: () -> String? = { null },
    onSaveFinished: (SaveOutcome) -> Unit = {},
): DocumentActions

internal enum class SaveOutcome { SAVED, FAILED, CANCELLED }

/** The file the user chose in the system picker. */
internal interface SaveTarget {
    /** Replaces the file's content; `false` or an exception means it may not have been written. */
    fun write(bytes: ByteArray): Boolean

    /**
     * Removes the file the picker created. Only when it is empty: a picker can also hand back an
     * existing file the user chose to overwrite, and that must survive a failed save.
     */
    fun deleteIfEmpty()
}

/**
 * Ends a save. Without content nothing is written, because opening the file for writing would
 * already empty it, and success is reported only after a complete write.
 */
internal fun finishSave(target: SaveTarget?, content: String?): SaveOutcome = when {
    target == null -> {
        SaveOutcome.CANCELLED
    }

    content == null -> {
        runCatching { target.deleteIfEmpty() }
        SaveOutcome.FAILED
    }

    runCatching { target.write(content.encodeToByteArray()) }.getOrDefault(false) -> {
        SaveOutcome.SAVED
    }

    else -> {
        SaveOutcome.FAILED
    }
}

/**
 * Text longer than this is shared as a file. An intent travels through a binder transaction limited
 * to about 1 MB for everything in flight, and two bytes per character plus the rest of the intent
 * must stay well below that; receiving apps often cope with even less.
 */
internal const val MAX_SHARE_TEXT_CHARS = 50_000

internal fun sharesAsFile(content: String): Boolean = content.length > MAX_SHARE_TEXT_CHARS

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
