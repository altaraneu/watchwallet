package com.altaran.watchwallet.core.model

import kotlin.time.Instant

/**
 * A tracked, watch-only wallet: a public address, an extended public key or a resolved name.
 * Never holds key material.
 */
data class Wallet(
    val id: Long,
    val name: String,
    val emoji: String?,
    val groupId: Long?,
    val notes: String?,
    val sortOrder: Int,
    val archived: Boolean,
    val createdAt: Instant,
)

data class WalletGroup(
    val id: Long,
    val name: String,
    val sortOrder: Int,
)
