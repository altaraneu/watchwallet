package com.altaran.watchwallet.core.data.chain

import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId
import com.altaran.watchwallet.core.model.WatchTarget

/**
 * All registered chain adapters. Adapters are looked up by chain id first (Bitcoin, Litecoin,
 * Dogecoin and Bitcoin Cash share the UTXO family; Cosmos and Polkadot adapters cover several
 * chains), then by family.
 */
class ChainRegistry(private val adapters: List<ChainAdapter>) {
    private val byChain: Map<ChainId, ChainAdapter> = buildMap {
        adapters.forEach { adapter -> adapter.chains.forEach { chain -> if (chain.id !in this) put(chain.id, adapter) } }
    }
    private val byFamily: Map<ChainFamily, ChainAdapter> = adapters.groupBy { it.family }.mapValues { it.value.first() }
    private val chainsById: Map<ChainId, Chain> = adapters.flatMap { it.chains }.associateBy { it.id }

    val supportedFamilies: Set<ChainFamily> get() = byFamily.keys

    fun adapter(target: WatchTarget): ChainAdapter? = byChain[target.chainId] ?: byFamily[target.family]

    fun supports(target: WatchTarget): Boolean = adapter(target) != null

    fun chain(id: ChainId): Chain? = chainsById[id]

    val allChains: Collection<Chain> get() = chainsById.values

    suspend fun resetHostHealth() = adapters.forEach { it.resetHostHealth() }
}

/** The chain a target is stored under (EVM targets use `evm:1` and cover all EVM networks). */
val WatchTarget.chainId: ChainId
    get() = when (this) {
        is WatchTarget.Address -> chainId
        is WatchTarget.ExtendedPublicKey -> chainId
    }
