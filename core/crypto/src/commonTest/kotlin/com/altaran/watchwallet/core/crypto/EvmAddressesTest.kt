package com.altaran.watchwallet.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class EvmAddressesTest {
    // Test vectors from EIP-55.
    private val eip55 = listOf(
        "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
    )

    @Test
    fun checksumMatchesEip55Vectors() {
        eip55.forEach { assertEquals(it, EvmAddresses.toChecksum(it.lowercase())) }
    }

    @Test
    fun validation() {
        eip55.forEach { assertEquals(EvmAddresses.Validation.Valid(it), EvmAddresses.validate(it)) }
        val lower = eip55.first().lowercase()
        assertEquals(EvmAddresses.Validation.Valid(eip55.first()), EvmAddresses.validate(lower))
        assertEquals(
            EvmAddresses.Validation.WrongChecksum,
            EvmAddresses.validate("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"),
        )
        assertEquals(EvmAddresses.Validation.Invalid, EvmAddresses.validate("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeA"))
    }
}
