package io.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** Cross-platform cache contract used by all Kache storage implementations. */
public interface KmpCache<K, V> {
    /**
     * Returns a flow of cache states for [key].
     *
     * The flow completes after emitting the states the chosen [strategy] calls for:
     * at most one cached state, followed by the network outcome when the strategy
     * refreshes.
     *
     * @param key Key to read.
     * @param strategy How cached and network data are balanced.
     * @param fetcher Loads fresh data on a miss. Null means the cache is read-only and a
     *   miss is reported as [KacheException.CacheMissException].
     */
    public fun get(
        key: K,
        strategy: CacheStrategy = CacheStrategy.CacheFirst,
        fetcher: (suspend (K) -> V)? = null
    ): Flow<CacheResult<V>>

    /**
     * Stores [value] for [key], optionally limiting its lifetime with [ttl].
     *
     * @param ttl How long the entry stays fresh, or null for the implementation's default.
     *   A null argument uses the TTL the cache was created with, not "no expiry".
     */
    public suspend fun put(key: K, value: V, ttl: Duration? = null)

    /** Removes the entry associated with [key]. */
    public suspend fun invalidate(key: K)

    /** Removes all entries managed by this cache. */
    public suspend fun clear()
}