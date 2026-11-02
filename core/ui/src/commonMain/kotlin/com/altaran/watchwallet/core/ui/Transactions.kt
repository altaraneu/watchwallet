package com.altaran.watchwallet.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.altaran.watchwallet.core.designsystem.component.AmountText
import com.altaran.watchwallet.core.designsystem.component.LocalDiscreetMode
import com.altaran.watchwallet.core.designsystem.theme.WatchWalletTheme
import com.altaran.watchwallet.core.model.QuoteCurrency
import com.altaran.watchwallet.core.model.TxDirection
import com.altaran.watchwallet.core.ui.format.DateFormatter
import com.altaran.watchwallet.core.ui.format.MoneyFormatter
import com.altaran.watchwallet.core.ui.generated.resources.Res
import com.altaran.watchwallet.core.ui.generated.resources.cost_dialog_cancel
import com.altaran.watchwallet.core.ui.generated.resources.cost_dialog_label
import com.altaran.watchwallet.core.ui.generated.resources.cost_dialog_label_per_coin
import com.altaran.watchwallet.core.ui.generated.resources.cost_dialog_message
import com.altaran.watchwallet.core.ui.generated.resources.cost_dialog_per_coin
import com.altaran.watchwallet.core.ui.generated.resources.cost_dialog_per_coin_hint
import com.altaran.watchwallet.core.ui.generated.resources.cost_dialog_reset
import com.altaran.watchwallet.core.ui.generated.resources.cost_dialog_save
import com.altaran.watchwallet.core.ui.generated.resources.cost_dialog_title
import com.altaran.watchwallet.core.ui.generated.resources.cost_dialog_total
import com.altaran.watchwallet.core.ui.generated.resources.cost_dialog_total_hint
import com.altaran.watchwallet.core.ui.generated.resources.tx_cost_auto
import com.altaran.watchwallet.core.ui.generated.resources.tx_cost_unknown
import com.altaran.watchwallet.core.ui.generated.resources.tx_cost_user
import com.altaran.watchwallet.core.ui.generated.resources.tx_label_pending
import com.altaran.watchwallet.core.ui.generated.resources.tx_open_explorer
import com.altaran.watchwallet.core.ui.generated.resources.tx_received
import com.altaran.watchwallet.core.ui.generated.resources.tx_self
import com.altaran.watchwallet.core.ui.generated.resources.tx_sent
import com.altaran.watchwallet.core.ui.generated.resources.tx_sheet_cost_auto
import com.altaran.watchwallet.core.ui.generated.resources.tx_sheet_cost_user
import com.altaran.watchwallet.core.ui.generated.resources.tx_sheet_edit_cost
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.time.Instant

/** One transaction leg prepared for display (wallet detail and asset detail share it). */
data class TxView(
    val walletId: Long,
    val hash: String,
    val assetId: String,
    val direction: TxDirection,
    val confirmed: Boolean,
    val time: Instant,
    /** Decimal amount, negative for outgoing. */
    val amount: Double,
    val symbol: String,
    val chainName: String?,
    /** Shown when the list mixes wallets (asset detail). */
    val walletName: String?,
    val costUsd: Double?,
    val costUserSet: Boolean,
    val explorerUrl: String?,
) {
    /** Incoming transfers carry a cost basis. */
    val hasCost: Boolean get() = amount > 0 && direction == TxDirection.IN
}

private val USD = QuoteCurrency.USD

/** "+" / "−" (typographic minus) prefix: gains and losses are never shown by color alone. */
private fun signed(amount: Double): String = when {
    amount > 0 -> "+"
    amount < 0 -> "−"
    else -> ""
}

/** Decimals of a cost total in the input field (US cents). */
private const val TOTAL_DECIMALS = 2

/** Significant digits kept when a per-coin price is prefilled. */
private const val PRICE_SIGNIFICANT_DIGITS = 8
private const val MAX_DECIMALS = 15

/** Thousands grouping in Swiss notation, for example 1'234.50. */
private const val APOSTROPHE = '\''

/** Above this, a value has no meaningful fraction in a Double; printed as a whole number. */
private const val MAX_FRACTION_MAGNITUDE = 1e15

/**
 * Plain decimal text for an input field: '.' separator, no grouping, never scientific notation,
 * rounded to [decimals] places with trailing zeros trimmed. For example 1.2345678e-5 with 10
 * decimals gives "0.0000123457".
 */
internal fun plainDecimal(value: Double, decimals: Int): String {
    if (!value.isFinite()) return ""
    val places = decimals.coerceIn(0, MAX_DECIMALS)
    val negative = value < 0
    val magnitude = abs(value)
    if (magnitude >= MAX_FRACTION_MAGNITUDE) return (if (negative) "-" else "") + magnitude.roundToLong().toString()
    var whole = floor(magnitude).toLong()
    val scale = 10.0.pow(places).roundToLong()
    var fraction = ((magnitude - whole) * scale).roundToLong()
    if (fraction >= scale) {
        whole += 1
        fraction -= scale
    }
    val fractionText = if (places == 0) "" else fraction.toString().padStart(places, '0').trimEnd('0')
    val sign = if (negative && (whole != 0L || fraction != 0L)) "-" else ""
    return sign + whole.toString() + if (fractionText.isEmpty()) "" else ".$fractionText"
}

/** Decimals that keep [PRICE_SIGNIFICANT_DIGITS] significant digits of [value]. */
internal fun priceDecimals(value: Double): Int {
    val magnitude = abs(value)
    if (magnitude == 0.0 || !magnitude.isFinite()) return TOTAL_DECIMALS
    val exponent = floor(log10(magnitude)).toInt()
    return (PRICE_SIGNIFICANT_DIGITS - 1 - exponent).coerceIn(TOTAL_DECIMALS, MAX_DECIMALS)
}

/**
 * Parses a number typed by the user. Accepts '.' and ',' as decimal separator and ignores spaces
 * (including no-break spaces) and apostrophes used for grouping. When both '.' and ',' appear,
 * the last one is the decimal separator; a separator repeated several times is grouping.
 */
internal fun parseDecimal(input: String): Double? {
    val compact = input.filterNot { it.isWhitespace() || it == Char(0x00A0) || it == Char(0x202F) || it == APOSTROPHE }
    if (compact.isEmpty()) return null
    val lastSeparator = maxOf(compact.lastIndexOf('.'), compact.lastIndexOf(','))
    val normalized = if (lastSeparator < 0) {
        compact
    } else {
        val decimalChar = compact[lastSeparator]
        val groupingChar = if (decimalChar == '.') ',' else '.'
        val withoutGrouping = compact.filterNot { it == groupingChar }
        if (withoutGrouping.count { it == decimalChar } > 1) {
            // "1,234,567": the only separator repeats, so it groups thousands.
            withoutGrouping.filterNot { it == decimalChar }
        } else {
            withoutGrouping.replace(decimalChar, '.')
        }
    }
    return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
}

@Composable
private fun directionLabel(direction: TxDirection): String = stringResource(
    when (direction) {
        TxDirection.IN -> Res.string.tx_received
        TxDirection.OUT -> Res.string.tx_sent
        TxDirection.SELF -> Res.string.tx_self
    },
)

/** Row of the activity list; the whole row is one tap target that opens [TransactionSheet]. */
@Composable
fun TransactionRow(tx: TxView, onClick: () -> Unit) {
    val colors = WatchWalletTheme.extendedColors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (tx.confirmed) directionLabel(tx.direction) else stringResource(Res.string.tx_label_pending, directionLabel(tx.direction)),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                listOfNotNull(DateFormatter.dateTime(tx.time), tx.walletName, tx.chainName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            AmountText(
                MoneyFormatter.isolated(signed(tx.amount) + MoneyFormatter.formatCrypto(abs(tx.amount), tx.symbol)),
                style = MaterialTheme.typography.titleSmall,
                color = if (tx.amount > 0) colors.positive else MaterialTheme.colorScheme.onSurface,
            )
            if (tx.hasCost) {
                val costStyle = MaterialTheme.typography.bodySmall
                val costColor = MaterialTheme.colorScheme.onSurfaceVariant
                if (tx.costUsd != null) {
                    // An amount: hidden in discreet mode like every other amount.
                    AmountText(
                        stringResource(if (tx.costUserSet) Res.string.tx_cost_user else Res.string.tx_cost_auto, MoneyFormatter.format(tx.costUsd, USD)),
                        style = costStyle,
                        color = costColor,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    Text(stringResource(Res.string.tx_cost_unknown), style = costStyle, color = costColor, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** Details of one transaction: amount, date, cost basis (editable) and the explorer link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionSheet(tx: TxView, onEditCost: () -> Unit, onOpenExplorer: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (tx.confirmed) directionLabel(tx.direction) else stringResource(Res.string.tx_label_pending, directionLabel(tx.direction)),
                style = MaterialTheme.typography.titleLarge,
            )
            AmountText(
                MoneyFormatter.isolated(signed(tx.amount) + MoneyFormatter.formatCrypto(abs(tx.amount), tx.symbol)),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                listOfNotNull(DateFormatter.dateTime(tx.time), tx.walletName, tx.chainName).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tx.hasCost) {
                HorizontalDivider()
                Text(stringResource(Res.string.cost_dialog_title), style = MaterialTheme.typography.titleSmall)
                val costText = tx.costUsd?.let {
                    stringResource(
                        if (tx.costUserSet) Res.string.tx_sheet_cost_user else Res.string.tx_sheet_cost_auto,
                        MoneyFormatter.format(it, USD),
                        MoneyFormatter.formatPrice(it / tx.amount, USD),
                        tx.symbol,
                    )
                }
                if (costText != null && LocalDiscreetMode.current) {
                    // AmountText hides it; the long sentence would not fit its single line anyway.
                    AmountText(costText, style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(costText ?: stringResource(Res.string.tx_cost_unknown), style = MaterialTheme.typography.bodyLarge)
                }
                FilledTonalButton(onClick = onEditCost, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.tx_sheet_edit_cost)) }
            }
            tx.explorerUrl?.let { url ->
                OutlinedButton(onClick = { onOpenExplorer(url) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.tx_open_explorer)) }
            }
        }
    }
}

/** Edit the USD cost of an incoming transfer (total or per coin), or return to the automatic value. */
@Composable
fun CostBasisDialog(tx: TxView, onSave: (Double?) -> Unit, onDismiss: () -> Unit) {
    val amount = tx.amount
    // Saveable drafts: rotating the device keeps what the user typed.
    var perCoin by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(tx.costUsd?.let { plainDecimal(it, TOTAL_DECIMALS) }.orEmpty()) }
    val entered = parseDecimal(text)
    val value = if (perCoin) entered?.let { it * amount } else entered
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.cost_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.cost_dialog_message), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !perCoin,
                        onClick = {
                            if (perCoin) entered?.let { text = plainDecimal(it * amount, TOTAL_DECIMALS) }
                            perCoin = false
                        },
                        label = { Text(stringResource(Res.string.cost_dialog_total)) },
                    )
                    FilterChip(
                        selected = perCoin,
                        onClick = {
                            if (!perCoin && amount > 0) entered?.let { text = (it / amount).let { price -> plainDecimal(price, priceDecimals(price)) } }
                            perCoin = true
                        },
                        label = { Text(stringResource(Res.string.cost_dialog_per_coin, tx.symbol)) },
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(if (perCoin) Res.string.cost_dialog_label_per_coin else Res.string.cost_dialog_label, tx.symbol)) },
                    supportingText = {
                        // Both forms visible: the total and the price per coin.
                        val other = when {
                            entered == null -> null
                            perCoin -> stringResource(Res.string.cost_dialog_total_hint, MoneyFormatter.format(entered * amount, USD))
                            amount > 0 -> stringResource(Res.string.cost_dialog_per_coin_hint, MoneyFormatter.formatPrice(entered / amount, USD), tx.symbol)
                            else -> null
                        }
                        other?.let { hint -> if (LocalDiscreetMode.current) AmountText(hint) else Text(hint) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = value != null && value >= 0) { Text(stringResource(Res.string.cost_dialog_save)) }
        },
        dismissButton = {
            Row {
                if (tx.costUserSet) TextButton(onClick = { onSave(null) }) { Text(stringResource(Res.string.cost_dialog_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cost_dialog_cancel)) }
            }
        },
    )
}

/** Sheet → dialog flow for a list of [TxView]s; call where the list is shown. */
@Composable
fun TransactionDetails(selected: TxView?, onSelect: (TxView?) -> Unit, onSetCost: (TxView, Double?) -> Unit, onOpenExplorer: (String) -> Unit) {
    var editing by remember { mutableStateOf<TxView?>(null) }
    selected?.let { tx ->
        TransactionSheet(
            tx = tx,
            onEditCost = {
                onSelect(null)
                editing = tx
            },
            onOpenExplorer = onOpenExplorer,
            onDismiss = { onSelect(null) },
        )
    }
    editing?.let { tx ->
        CostBasisDialog(
            tx = tx,
            onSave = { value ->
                onSetCost(tx, value)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}
