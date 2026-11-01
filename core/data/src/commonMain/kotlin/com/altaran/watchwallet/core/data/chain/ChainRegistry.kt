package com.altaran.watchwallet.core.data.chain

import com.altaran.watchwallet.chain.api.ChainAdapter
import com.altaran.watchwallet.core.model.Chain
import com.altaran.watchwallet.core.model.ChainFamily
import com.altaran.watchwallet.core.model.ChainId

/** All registered chain adapters; the single place that maps families and chain ids to adapters. */
class ChainRegistry(adapters: List<ChainAdapter>) {
    private val byFamily: Map<ChainFamily, ChainAdapter> = adapters.associateBy { it.family }
    private val chainsById: Map<ChainId, Chain> = adapters.flatMap { it.chains }.associateBy { it.id }

    val supportedFamilies: Set<ChainFamily> get() = byFamily.keys

    fun adapter(family: ChainFamily): ChainAdapter? = byFamily[family]

    fun chain(id: ChainId): Chain? = chainsById[id]

    val allChains: Collection<Chain> get() = chainsById.values
}
