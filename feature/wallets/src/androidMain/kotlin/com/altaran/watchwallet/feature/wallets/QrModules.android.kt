package com.altaran.watchwallet.feature.wallets

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

internal actual fun qrModules(text: String): List<BooleanArray>? = runCatching {
    // The composable draws the quiet zone itself.
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
}.getOrNull()
