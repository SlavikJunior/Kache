package com.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.Flow

/** Cross-platform cache contract used by all Kache storage implementations. */
public interface KmpCache<K, V> {
    /** Returns a flow of cache states for [key]. */
    public fun get(
        key: K,
        strategy: CacheStrategy,
        fetcher: (suspend (K) -> V)?
    ): Flow<CacheResult<V>>

    /** Stores [value] for [key], optionally limiting its lifetime with [ttlMs]. */
    public suspend fun put(key: K, value: V, ttlMs: Long? = null)

    /** Removes the entry associated with [key]. */
    public suspend fun invalidate(key: K)

    /** Removes all entries managed by this cache. */
    public suspend fun clear()
}