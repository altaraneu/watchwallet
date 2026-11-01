package com.altaran.watchwallet.core.model

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Exact on-chain amount in the asset's smallest unit (satoshi, wei, lamport…) plus decimals.
 * Never use floating point for balances; convert to [Double] only for fiat value display.
 */
data class TokenAmount(
    val raw: BigInteger,
    val decimals: Int,
) : Comparable<TokenAmount> {
    /** Exact decimal value, for example `0.00012345` BTC. */
    fun toBigDecimal(): BigDecimal =
        BigDecimal.fromBigInteger(raw).divide(BigDecimal.TEN.pow(decimals.toLong()), DECIMAL_MODE)

    fun toDouble(): Double = toBigDecimal().doubleValue(exactRequired = false)

    /** Plain decimal string without exponent and without trailing zeros, for example `1.5`. */
    fun toPlainString(): String = toBigDecimal().toPlainString().let { text ->
        if ('.' in text) text.trimEnd('0').trimEnd('.') else text
    }

    val isZero: Boolean get() = raw.isZero()

    operator fun plus(other: TokenAmount): TokenAmount {
        require(decimals == other.decimals) { "Decimals mismatch" }
        return TokenAmount(raw + other.raw, decimals)
    }

    override fun compareTo(other: TokenAmount): Int = toBigDecimal().compareTo(other.toBigDecimal())

    companion object {
        private val DECIMAL_MODE = DecimalMode(decimalPrecision = 60, roundingMode = RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)

        fun zero(decimals: Int) = TokenAmount(BigInteger.ZERO, decimals)

        fun parseRaw(raw: String, decimals: Int) = TokenAmount(BigInteger.parseString(raw.ifBlank { "0" }), decimals)

        /** Parses a decimal string such as `0.5` into the smallest unit. */
        fun parseDecimal(value: String, decimals: Int): TokenAmount {
            val scaled = BigDecimal.parseString(value).moveDecimalPoint(decimals)
            return TokenAmount(scaled.toBigInteger(), decimals)
        }
    }
}
