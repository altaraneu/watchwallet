package com.altaran.watchwallet.feature.importexport

import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

@Composable
internal actual fun rememberDocumentActions(): DocumentActions {
    val context = LocalContext.current
    val holder = remember { Holder() }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val text = uri?.let {
            runCatching {
                context.contentResolver.openInputStream(it)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > MAX_IMPORT_BYTES) null else bytes.decodeToString()
                }
            }.getOrNull()
        }
        holder.onOpen?.invoke(text)
        holder.onOpen = null
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val ok = uri != null && runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(holder.saveContent.orEmpty().toByteArray()) } != null
        }.getOrDefault(false)
        holder.onSave?.invoke(ok)
        holder.onSave = null
        holder.saveContent = null
    }
    return remember {
        object : DocumentActions {
            override fun openText(onResult: (String?) -> Unit) {
                holder.onOpen = onResult
                openLauncher.launch(arrayOf("text/*", "application/json", "text/csv", "text/comma-separated-values", "application/octet-stream"))
            }

            override fun saveText(fileName: String, mimeType: String, content: String, onResult: (Boolean) -> Unit) {
                holder.saveContent = content
                holder.onSave = onResult
                saveLauncher.launch(fileName)
            }

            override fun shareText(content: String) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

private class Holder {
    var onOpen: ((String?) -> Unit)? = null
    var onSave: ((Boolean) -> Unit)? = null
    var saveContent: String? = null
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
