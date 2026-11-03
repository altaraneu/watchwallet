package com.altaran.watchwallet.feature.importexport

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val READ_BUFFER_BYTES = 8 * 1024
private const val EXPORT_DIRECTORY = "exports"
private const val EXPORT_AUTHORITY_SUFFIX = ".exports"
private const val SHARED_FILE_MAX_AGE_MS = 60 * 60 * 1000L

/** The whole stream, or `null` as soon as it turns out to be longer than [limit]. */
internal fun readBounded(input: InputStream, limit: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return output.toByteArray()
        if (output.size() + read > limit) return null
        output.write(buffer, 0, read)
    }
}

private class DocumentSaveTarget(private val resolver: ContentResolver, private val uri: Uri) : SaveTarget {
    override fun write(bytes: ByteArray): Boolean = resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null

    override fun deleteIfEmpty() {
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { row ->
            if (row.moveToFirst() && !row.isNull(0)) row.getLong(0) else null
        }
        if (size == 0L) DocumentsContract.deleteDocument(resolver, uri)
    }
}

/**
 * Files handed to other apps through the share sheet. The receiver reads them at some later moment
 * nobody reports, so they are removed by age whenever the export screen opens or shares again.
 */
private class SharedExports(private val context: Context) {
    private val directory get() = File(context.cacheDir, EXPORT_DIRECTORY)

    fun deleteOld(now: Long = System.currentTimeMillis()) {
        directory.listFiles()?.filter { now - it.lastModified() > SHARED_FILE_MAX_AGE_MS }?.forEach { it.delete() }
    }

    fun write(fileName: String, content: String): Uri {
        directory.mkdirs()
        // Only the name part: nothing may lead outside the shared directory.
        val file = File(directory, File(fileName).name)
        file.writeText(content)
        return FileProvider.getUriForFile(context, context.packageName + EXPORT_AUTHORITY_SUFFIX, file)
    }
}

@Composable
internal actual fun rememberDocumentActions(
    takeSaveContent: () -> String?,
    onSaveFinished: (SaveOutcome) -> Unit,
    onOpenFinished: (OpenOutcome) -> Unit,
): DocumentActions {
    val context = LocalContext.current
    val sharedExports = remember(context) { SharedExports(context).also { runCatching { it.deleteOld() } } }
    val finished = rememberUpdatedState(onSaveFinished)
    val take = rememberUpdatedState(takeSaveContent)
    val opened = rememberUpdatedState(onOpenFinished)

    // Registered with a saved key, so the results also arrive after rotation or process death: then
    // these callbacks belong to a new composition and possibly to a new view model.
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val source = uri?.let {
            OpenSource { limit ->
                val input = context.contentResolver.openInputStream(it) ?: throw FileNotFoundException()
                input.use { stream -> readBounded(stream, limit) }
            }
        }
        onOpenFinished(finishOpen(source))
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        onSaveFinished(finishSave(uri?.let { DocumentSaveTarget(context.contentResolver, it) }, takeSaveContent()))
    }
    return remember {
        object : DocumentActions {
            override fun openText() {
                // No app handles the request on some stripped-down systems.
                runCatching {
                    openLauncher.launch(arrayOf("text/*", "application/json", "text/csv", "text/comma-separated-values", "application/octet-stream"))
                }.onFailure { opened.value(OpenOutcome.Failed) }
            }

            override fun createDocument(fileName: String, mimeType: String) {
                // No app handles the request on some stripped-down systems.
                runCatching { saveLauncher.launch(fileName) }.onFailure {
                    take.value()
                    finished.value(SaveOutcome.FAILED)
                }
            }

            override fun share(fileName: String, mimeType: String, content: String): Boolean = runCatching {
                val send = Intent(Intent.ACTION_SEND)
                if (sharesAsFile(content)) {
                    sharedExports.deleteOld()
                    val uri = sharedExports.write(fileName, content)
                    send.type = mimeType
                    send.putExtra(Intent.EXTRA_STREAM, uri)
                    send.clipData = ClipData.newRawUri(null, uri)
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    send.type = "text/plain"
                    send.putExtra(Intent.EXTRA_TEXT, content)
                }
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    }
}

internal actual object ExportCrypto {
    private const val FORMAT = "watchwallet-encrypted"
    private const val ITERATIONS = 600_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Accepted iteration counts: rejects weak files and absurd values that would hang the app. */
    private val ITERATION_RANGE = 100_000..10_000_000

    actual val isSupported: Boolean = true

    actual fun encrypt(plainText: String, password: String): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plainText.toByteArray())
        return JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("kdf", "PBKDF2-HMAC-SHA256")
            .put("iterations", ITERATIONS)
            .put("cipher", "AES-256-GCM")
            .put("salt", b64(salt))
            .put("iv", b64(iv))
            .put("ciphertext", b64(ciphertext))
            .toString(2)
    }

    actual fun decrypt(envelope: String, password: String): String? = runCatching {
        val json = JSONObject(envelope)
        require(json.getString("format") == FORMAT)
        val iterations = json.getInt("iterations")
        require(iterations in ITERATION_RANGE)
        val salt = Base64.decode(json.getString("salt"), Base64.NO_WRAP)
        require(salt.size == SALT_BYTES)
        val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
        require(iv.size == IV_BYTES)
        val data = Base64.decode(json.getString("ciphertext"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(data).decodeToString()
    }.getOrNull()

    actual fun isEncrypted(text: String): Boolean = text.contains("\"$FORMAT\"")

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
