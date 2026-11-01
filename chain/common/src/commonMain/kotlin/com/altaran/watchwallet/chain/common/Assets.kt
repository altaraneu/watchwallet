package com.altaran.watchwallet.chain.common

import com.altaran.watchwallet.chain.api.AssetAmount
import com.altaran.watchwallet.chain.api.ChainTransaction
import com.altaran.watchwallet.core.model.Asset
import com.altaran.watchwallet.core.model.AssetId
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.TokenAmount
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.time.Instant

/** The native asset of a chain, described by its [Chain] metadata. */
fun nativeAsset(chain: Chain, name: String = chain.name): Asset = Asset(
    id = AssetId.native(chain.id),
    chainId = chain.id,
    symbol = chain.nativeSymbol,
    name = name,
    decimals = chain.nativeDecimals,
    priceId = chain.nativePriceId,
)

/**
 * A token asset. [contract] keeps its original case (base58 mints are case-sensitive); only the
 * [AssetId] is lowercased by [AssetId.token]. Colons are not allowed in the id.
 */
fun tokenAsset(
    chainId: ChainId,
    contract: String,
    symbol: String?,
    name: String?,
    decimals: Int,
    priceId: String? = null,
): Asset = Asset(
    id = AssetId.token(chainId, contract.replace(':', '.')),
    chainId = chainId,
    symbol = symbol?.trim()?.take(MAX_SYMBOL)?.ifEmpty { null } ?: shortId(contract),
    name = name?.trim()?.take(MAX_NAME)?.ifEmpty { null } ?: contract,
    decimals = decimals,
    priceId = priceId,
    contract = contract,
)

/** Price id of a token priced by contract on a CoinGecko platform: `token:<platform>:<contract>`. */
fun tokenPriceId(platform: String, contract: String): String = "token:$platform:$contract"

fun amountOf(asset: Asset, raw: BigInteger): AssetAmount = AssetAmount(asset, TokenAmount(raw, asset.decimals))

fun amountOf(asset: Asset, raw: String?): AssetAmount = amountOf(asset, parseBigInteger(raw))

/** Parses an integer string; blank or malformed values become zero. */
fun parseBigInteger(raw: String?): BigInteger =
    raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { BigInteger.parseString(it) }.getOrNull() }
        ?: BigInteger.ZERO

/** Parses a decimal string such as `12.5` or `1.2e-7` into base units with [decimals]. */
fun parseDecimalUnits(value: String?, decimals: Int): BigInteger {
    val text = value?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return BigInteger.ZERO
    return runCatching { TokenAmount.parseDecimal(text, decimals).raw }.getOrDefault(BigInteger.ZERO)
}

fun shortId(id: String): String = if (id.length <= 10) id else "${id.take(4)}…${id.takeLast(4)}"

/** Convenience constructor for account-model chains (no inputs/outputs). */
fun accountTransaction(
    chainId: ChainId,
    hash: String,
    timestamp: Instant,
    asset: Asset,
    delta: BigInteger,
    fee: BigInteger?,
    confirmed: Boolean = true,
    isSelfTransfer: Boolean = false,
) = ChainTransaction(chainId, hash, timestamp, asset, delta, fee, isSelfTransfer, confirmed)

private const val MAX_SYMBOL = 16
private const val MAX_NAME = 64
