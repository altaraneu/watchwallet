package com.altaran.watchwallet.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-flight deduplication: concurrent callers with the same key share one execution of the block.
 * It runs in its own scope, so a caller leaving (a closed screen) does not fail the others.
 */
class SingleFlight<K : Any, V>(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    private val flights = HashMap<K, Deferred<V>>()
    private val lock = Mutex()

    suspend fun run(key: K, block: suspend () -> V): V {
        val flight = lock.withLock {
            flights.getOrPut(key) {
                scope.async {
                    try {
                        block()
                    } finally {
                        lock.withLock { flights.remove(key) }
                    }
                }
            }
        }
        return flight.await()
    }
}
