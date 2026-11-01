package com.altaran.watchwallet.feature.addwallet

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.feature.addwallet.generated.resources.Res
import com.altaran.watchwallet.feature.addwallet.generated.resources.qr_close
import com.altaran.watchwallet.feature.addwallet.generated.resources.qr_hint
import com.altaran.watchwallet.feature.addwallet.generated.resources.qr_no_permission
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.jetbrains.compose.resources.stringResource
import java.util.concurrent.Executors

internal actual val isQrScanningSupported: Boolean = true

@Composable
internal actual fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                granted -> CameraPreview(onResult)

                denied -> Text(
                    stringResource(Res.string.qr_no_permission),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            Box(
                Modifier.align(Alignment.Center).size(240.dp)
                    .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(24.dp)),
            )
            Text(
                stringResource(Res.string.qr_hint),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.BottomCenter).padding(48.dp),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Icon(WwIcon.ArrowBack.painter(), contentDescription = stringResource(Res.string.qr_close), tint = Color.White)
            }
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onResult)
    val executor = remember { Executors.newSingleThreadExecutor() }
    var delivered by remember { mutableStateOf(false) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    val text = decode(reader, image)
                    image.close()
                    if (text != null && !delivered) {
                        delivered = true
                        ContextCompat.getMainExecutor(ctx).execute { callback(text) }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
}

/** Decodes a QR code from the luminance (Y) plane of a camera frame. */
private fun decode(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
    return runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull().also { reader.reset() }
}
