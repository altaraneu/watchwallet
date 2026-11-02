package com.altaran.watchwallet.feature.addwallet

import androidx.compose.runtime.Composable

/** `true` when this platform can scan QR codes with the camera. */
internal expect val isQrScanningSupported: Boolean

/**
 * Full-screen QR scanner. Calls [onResult] once with the decoded text and, when the code holds raw
 * bytes, those bytes (the add-wallet flow runs both through the secret guard and classifier like
 * pasted text), or [onDismiss].
 */
@Composable
internal expect fun QrScannerDialog(onResult: (String, ByteArray?) -> Unit, onDismiss: () -> Unit)
