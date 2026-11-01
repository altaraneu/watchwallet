package com.altaran.watchwallet.feature.importexport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// TODO(iOS): UIDocumentPickerViewController and UIActivityViewController.
@Composable
internal actual fun rememberDocumentActions(): DocumentActions = remember {
    object : DocumentActions {
        override fun openText(onResult: (String?) -> Unit) = onResult(null)

        override fun saveText(fileName: String, mimeType: String, content: String, onResult: (Boolean) -> Unit) = onResult(false)

        override fun shareText(content: String) = Unit
    }
}

// TODO(iOS): CommonCrypto PBKDF2 + CryptoKit AES-GCM with the same envelope format as Android.
internal actual object ExportCrypto {
    actual val isSupported: Boolean = false

    actual fun encrypt(plainText: String, password: String): String = throw UnsupportedOperationException("Not available on iOS yet")

    actual fun decrypt(envelope: String, password: String): String? = null

    actual fun isEncrypted(text: String): Boolean = text.contains("\"watchwallet-encrypted\"")
}
