package com.altaran.watchwallet.feature.importexport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

// TODO(iOS): UIDocumentPickerViewController and UIActivityViewController.
@Composable
internal actual fun rememberDocumentActions(
    takeSaveContent: () -> String?,
    onSaveFinished: (SaveOutcome) -> Unit,
    onOpenFinished: (OpenOutcome) -> Unit,
): DocumentActions {
    val finished = rememberUpdatedState(onSaveFinished)
    val take = rememberUpdatedState(takeSaveContent)
    val opened = rememberUpdatedState(onOpenFinished)
    return remember {
        object : DocumentActions {
            override fun openText() = opened.value(OpenOutcome.Failed)

            override fun createDocument(fileName: String, mimeType: String) {
                take.value()
                finished.value(SaveOutcome.FAILED)
            }

            override fun share(fileName: String, mimeType: String, content: String): Boolean = false
        }
    }
}

// TODO(iOS): CommonCrypto PBKDF2 + CryptoKit AES-GCM with the same envelope format as Android.
internal actual object ExportCrypto {
    actual val isSupported: Boolean = false

    actual fun encrypt(plainText: String, password: String): String = throw UnsupportedOperationException("Not available on iOS yet")

    actual fun decrypt(envelope: String, password: String): String? = null

    actual fun isEncrypted(text: String): Boolean = text.contains("\"watchwallet-encrypted\"")
}
