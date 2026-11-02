package com.altaran.watchwallet.core.pricing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Keeps the marks of [CoinGeckoUsage] across app starts: prices stay stored, so their source must too. */
interface PriceSourceStore {
    val marks: Flow<Set<String>>

    suspend fun update(transform: (Set<String>) -> Set<String>)
}

class InMemoryPriceSourceStore : PriceSourceStore {
    private val state = MutableStateFlow(emptySet<String>())

    override val marks: Flow<Set<String>> = state

    override suspend fun update(transform: (Set<String>) -> Set<String>) = state.update(transform)
}

/**
 * Which assets have stored prices that came from CoinGecko, whose API terms require crediting it
 * wherever its data is displayed.
 *
 * A current price counts until another source replaces it. Price history only ever counts up: its
 * points stay stored, and older ones are not replaced.
 */
class CoinGeckoUsage(private val store: PriceSourceStore = InMemoryPriceSourceStore()) {
    val priceIds: Flow<Set<String>> = store.marks
        .map { marks -> marks.mapTo(HashSet()) { it.substringAfter(SEPARATOR) } as Set<String> }
        .distinctUntilChanged()

    suspend fun servedCurrent(priceIds: Collection<String>) {
        if (priceIds.isNotEmpty()) store.update { it + priceIds.map(::currentMark) }
    }

    suspend fun replacedCurrent(priceIds: Collection<String>) {
        if (priceIds.isNotEmpty()) store.update { it - priceIds.map(::currentMark).toSet() }
    }

    suspend fun servedHistory(priceId: String) = store.update { it + historyMark(priceId) }

    private fun currentMark(priceId: String) = "current$SEPARATOR$priceId"

    private fun historyMark(priceId: String) = "history$SEPARATOR$priceId"

    private companion object {
        const val SEPARATOR = '|'
    }
}
