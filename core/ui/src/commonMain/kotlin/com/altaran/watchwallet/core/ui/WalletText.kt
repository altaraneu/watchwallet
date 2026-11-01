package com.altaran.watchwallet.core.ui

import androidx.compose.runtime.Composable
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ScriptType
import com.altaran.watchwallet.core.model.WatchTarget
import com.altaran.watchwallet.core.ui.generated.resources.Res
import com.altaran.watchwallet.core.ui.generated.resources.family_evm
import com.altaran.watchwallet.core.ui.generated.resources.family_utxo_bitcoin
import com.altaran.watchwallet.core.ui.generated.resources.script_p2pkh
import com.altaran.watchwallet.core.ui.generated.resources.script_p2sh
import com.altaran.watchwallet.core.ui.generated.resources.script_p2sh_p2wpkh
import com.altaran.watchwallet.core.ui.generated.resources.script_p2tr
import com.altaran.watchwallet.core.ui.generated.resources.script_p2wpkh
import com.altaran.watchwallet.core.ui.generated.resources.script_p2wsh
import com.altaran.watchwallet.core.ui.generated.resources.target_address
import com.altaran.watchwallet.core.ui.generated.resources.target_description
import com.altaran.watchwallet.core.ui.generated.resources.target_xpub
import org.jetbrains.compose.resources.stringResource

/** "Native SegWit", "Taproot"… */
@Composable
fun scriptTypeLabel(type: ScriptType): String = stringResource(
    when (type) {
        ScriptType.P2PKH -> Res.string.script_p2pkh
        ScriptType.P2SH_P2WPKH -> Res.string.script_p2sh_p2wpkh
        ScriptType.P2WPKH -> Res.string.script_p2wpkh
        ScriptType.P2TR -> Res.string.script_p2tr
        ScriptType.P2SH -> Res.string.script_p2sh
        ScriptType.P2WSH -> Res.string.script_p2wsh
    },
)

/** Name of the chain family as shown to users ("Bitcoin", "EVM"). */
@Composable
fun familyLabel(family: ChainFamily): String = when (family) {
    ChainFamily.UTXO -> stringResource(Res.string.family_utxo_bitcoin)
    ChainFamily.EVM -> stringResource(Res.string.family_evm)
    else -> family.name.lowercase().replaceFirstChar { it.uppercase() }
}

/** Product names of chains (proper nouns, not translated). */
private val CHAIN_NAMES = mapOf(
    "bitcoin" to "Bitcoin", "litecoin" to "Litecoin", "dogecoin" to "Dogecoin", "bitcoincash" to "Bitcoin Cash",
    "solana" to "Solana", "xrp" to "XRP Ledger", "tron" to "Tron", "ton" to "TON", "cardano" to "Cardano",
    "cosmos:cosmoshub" to "Cosmos Hub", "cosmos:osmosis" to "Osmosis", "cosmos:celestia" to "Celestia",
    "cosmos:injective" to "Injective", "polkadot" to "Polkadot", "kusama" to "Kusama", "stellar" to "Stellar",
    "sui" to "Sui", "aptos" to "Aptos", "near" to "NEAR", "hedera" to "Hedera", "algorand" to "Algorand",
    "tezos" to "Tezos", "kaspa" to "Kaspa",
)

/** Display name of the chain a target lives on; EVM targets cover all EVM networks. */
@Composable
fun chainLabel(target: WatchTarget): String {
    val chainId = when (target) {
        is WatchTarget.Address -> target.chainId
        is WatchTarget.ExtendedPublicKey -> target.chainId
    }
    return if (target.family == ChainFamily.EVM) familyLabel(ChainFamily.EVM) else CHAIN_NAMES[chainId.value] ?: familyLabel(target.family)
}

/** For example "Bitcoin · Native SegWit xpub", "Solana · address" or "EVM · address". */
@Composable
fun targetDescription(target: WatchTarget): String {
    val kind = when (target) {
        is WatchTarget.ExtendedPublicKey -> stringResource(Res.string.target_xpub, scriptTypeLabel(target.scriptType))
        is WatchTarget.Address -> target.scriptType?.let { stringResource(Res.string.target_address, scriptTypeLabel(it)) }
            ?: stringResource(Res.string.target_address, "").trim()
    }
    return stringResource(Res.string.target_description, chainLabel(target), kind)
}

/** Shortens an address for display: `bc1qar0s…wf5mdq`. */
fun shortAddress(value: String, head: Int = 8, tail: Int = 6): String =
    if (value.length <= head + tail + 1) value else value.take(head) + "…" + value.takeLast(tail)
