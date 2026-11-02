package com.altaran.watchwallet.feature.wallets

/** Modules of a QR code (rows of dark/light), or `null` where the platform has no encoder yet. */
internal expect fun qrModules(text: String): List<BooleanArray>?
