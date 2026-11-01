package com.altaran.watchwallet.core.crypto

import org.kotlincrypto.hash.sha3.Keccak256

/** Ethereum-style (EVM) address validation and EIP-55 checksum encoding. */
object EvmAddresses {
    private val SHAPE = Regex("""^0x[0-9a-fA-F]{40}$""")

    sealed interface Validation {
        data class Valid(val checksummed: String) : Validation
        data object WrongChecksum : Validation
        data object Invalid : Validation
    }

    /**
     * All-lowercase or all-uppercase addresses carry no checksum and are accepted; mixed-case
     * addresses must match their EIP-55 checksum exactly.
     */
    fun validate(input: String): Validation {
        if (!SHAPE.matches(input)) return Validation.Invalid
        val body = input.substring(2)
        val checksummed = toChecksum(input)
        val hasChecksum = body != body.lowercase() && body != body.uppercase()
        return if (hasChecksum && checksummed != input) Validation.WrongChecksum else Validation.Valid(checksummed)
    }

    /** EIP-55 mixed-case checksum encoding. */
    fun toChecksum(address: String): String {
        val lower = address.removePrefix("0x").removePrefix("0X").lowercase()
        val hash = Keccak256().digest(lower.encodeToByteArray())
        val result = StringBuilder("0x")
        lower.forEachIndexed { i, char ->
            val nibble = (hash[i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0x0F
            result.append(if (char.isLetter() && nibble >= 8) char.uppercaseChar() else char)
        }
        return result.toString()
    }
}
