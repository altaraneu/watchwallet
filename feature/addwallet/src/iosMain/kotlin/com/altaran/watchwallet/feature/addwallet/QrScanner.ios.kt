package com.altaran.watchwallet.feature.addwallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

// TODO(iOS): AVCaptureSession with AVMetadataObjectTypeQRCode.
internal actual val isQrScanningSupported: Boolean = false

@Composable
internal actual fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onDismiss() }
}
