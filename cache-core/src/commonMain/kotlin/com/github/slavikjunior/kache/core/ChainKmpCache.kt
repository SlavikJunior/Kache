package com.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Two-tier cache combining L1 (in-memory) and L2 (persistent) storage.
 *
 * A read is served from the cheapest tier that holds usable data:
 * 1. L1 memory hit emits [CacheOrigin.MEMORY].
 * 2. L1 miss falls through to L2; a hit is promoted into L1 and emits [CacheOrigin.DISK].
 * 3. L2 miss invokes the fetcher, writes through to both tiers, and emits [CacheOrigin.NETWORK].
 *
 * Promotion preserves the original creation time of the L2 record, so moving a value
 * between tiers never extends its lifetime.
 *
 * @param K Key type.
 * @param V Value type.
 * @param l1Cache In-memory tier.
 * @param l2Storage Persistent tier.
 * @param serializer Serializer used to encode values for L2 and decode them on read.
 * @param keyToString Maps a typed key to the string key used by L2. Override when the
 *   default [Any.toString] is not a stable or safe storage key.
 * @param defaultTtlMs TTL in milliseconds applied on write when the caller gives none.
 *   Null means entries never expire.
 */
public class ChainKmpCache<K, V>(
    internal val l1Cache: L1MemoryCache<K, V>,
    internal val l2Storage: StorageEngine,
    private val serializer: KacheSerializer<V>,
    private val keyToString: (K) -> String = { it.toString() },
    private val defaultTtlMs: Long? = null,
) : KmpCache<K, V> {

    override fun get(
        key: K,
        strategy: CacheStrategy,
        fetcher: (suspend (K) -> V)?,
    ): Flow<CacheResult<V>> = when (strategy) {
        CacheStrategy.CacheFirst -> cacheFirst(key, fetcher)
        CacheStrategy.NetworkFirst -> networkFirst(key, fetcher)
        CacheStrategy.CacheAndNetwork -> cacheAndNetwork(key, fetcher)
        CacheStrategy.StaleWhileRevalidate -> staleWhileRevalidate(key, fetcher)
    }

    /**
     * Serves fresh cache data when available, otherwise fetches.
     *
     * An expired record in either tier is treated as a miss rather than served, so a
     * client never receives data it would have to discard.
     */
    private fun cacheFirst(key: K, fetcher: (suspend (K) -> V)?): Flow<CacheResult<V>> = flow {
        readCache(key, allowStale = false)?.let { cached ->
            emit(CacheResult.Success(cached.value, cached.origin))
            return@flow
        }

        when (val outcome = fetch(key, fetcher)) {
            is FetchOutcome.Success -> {
                write(key, outcome.value, defaultTtlMs)
                emit(CacheResult.Success(outcome.value, CacheOrigin.NETWORK))
            }
            is FetchOutcome.Failure -> emit(CacheResult.Error(KacheException.NetworkException(outcome.cause)))
            FetchOutcome.Absent -> emit(CacheResult.Error(KacheException.CacheMissException(NO_FETCHER_MESSAGE)))
        }
    }

    /**
     * Fetches first and only reads cache when the fetch does not succeed.
     *
     * A network failure is not reported to the caller when cached data can take its
     * place; the cached value is emitted with its real origin instead.
     */
    private fun networkFirst(key: K, fetcher: (suspend (K) -> V)?): Flow<CacheResult<V>> = flow {
        when (val outcome = fetch(key, fetcher)) {
            is FetchOutcome.Success -> {
                write(key, outcome.value, defaultTtlMs)
                emit(CacheResult.Success(outcome.value, CacheOrigin.NETWORK))
                return@flow
            }
            is FetchOutcome.Failure, FetchOutcome.Absent -> Unit
        }

        readCache(key, allowStale = false)?.let { cached ->
            emit(CacheResult.Success(cached.value, cached.origin))
            return@flow
        }

        emit(CacheResult.Error(KacheException.CacheMissException(NO_DATA_AFTER_FETCH_MESSAGE)))
    }

    /**
     * Emits cached data when present, then always emits the network result.
     *
     * The point of this strategy is to show something immediately while still getting
     * fresh data, so the fetch is never skipped and its result is always emitted. A
     * fetch failure is only surfaced when there is no cached value to fall back on.
     */
    private fun cacheAndNetwork(key: K, fetcher: (suspend (K) -> V)?): Flow<CacheResult<V>> = flow {
        val cached = readCache(key, allowStale = false)
        if (cached != null) {
            emit(CacheResult.Success(cached.value, cached.origin))
        }

        when (val outcome = fetch(key, fetcher)) {
            is FetchOutcome.Success -> {
                write(key, outcome.value, defaultTtlMs)
                emit(CacheResult.Success(outcome.value, CacheOrigin.NETWORK))
            }
            is FetchOutcome.Failure -> if (cached == null) {
                emit(CacheResult.Error(KacheException.NetworkException(outcome.cause)))
            }
            FetchOutcome.Absent -> if (cached == null) {
                emit(CacheResult.Error(KacheException.CacheMissException(NO_FETCHER_MESSAGE)))
            }
        }
    }

    /**
     * Emits cached data even when it has already expired, then refreshes.
     *
     * Stale values carry a `_STALE` origin so consumers can tell that a refresh follows.
     * The stale record stays in L1 while the fetch runs: a successful fetch replaces it,
     * a failed one leaves it in place for the next attempt.
     */
    private fun staleWhileRevalidate(key: K, fetcher: (suspend (K) -> V)?): Flow<CacheResult<V>> = flow {
        val cached = readCache(key, allowStale = true)
        if (cached != null) {
            emit(CacheResult.Success(cached.value, cached.origin))
        }

        when (val outcome = fetch(key, fetcher)) {
            is FetchOutcome.Success -> {
                write(key, outcome.value, defaultTtlMs)
                emit(CacheResult.Success(outcome.value, CacheOrigin.NETWORK))
            }
            is FetchOutcome.Failure -> if (cached == null) {
                emit(CacheResult.Error(KacheException.NetworkException(outcome.cause)))
            }
            FetchOutcome.Absent -> if (cached == null) {
                emit(CacheResult.Error(KacheException.CacheMissException(NO_FETCHER_MESSAGE)))
            }
        }
    }

    /**
     * Resolves a value from cache, promoting from L2 into L1 on an L1 miss.
     *
     * @param allowStale When true, expired records are returned and retained. When false,
     *   an expired record is discarded and reported as a miss.
     * @return The value and the tier it came from, or null on a miss.
     */
    private suspend fun readCache(key: K, allowStale: Boolean): Cached<V>? {
        val now = l1Cache.currentTimeMillis()

        // getStale is used when the caller can tolerate expired data, so the entry is
        // not evicted before the strategy has had a chance to serve it.
        val l1Record = if (allowStale) l1Cache.getStale(key) else l1Cache.get(key)
        if (l1Record != null) {
            val stale = l1Record.isExpired(now)
            val origin = if (stale) CacheOrigin.MEMORY_STALE else CacheOrigin.MEMORY
            return Cached(l1Record.value, origin)
        }

        val l2Record = l2Storage.get(keyToString(key)) ?: return null
        val stale = l2Record.isExpired(now)
        if (stale && !allowStale) return null

        val value = serializer.deserialize(l2Record.data)
        promoteToL1(key, value, l2Record)

        val origin = if (stale) CacheOrigin.DISK_STALE else CacheOrigin.DISK
        return Cached(value, origin)
    }

    /**
     * Copies an L2 record into L1 while keeping its absolute expiry.
     *
     * The TTL handed to L1 is the *remaining* time rather than the original TTL, so
     * promoting a record never grants it a fresh full-length lifetime. An entry that is
     * already expired is stored with a zero TTL: it stays readable through
     * [L1MemoryCache.getStale] for revalidation, but [L1MemoryCache.get] will not return it.
     */
    private suspend fun promoteToL1(key: K, value: V, record: StorageRecord<*>) {
        val now = l1Cache.currentTimeMillis()
        val expiresAt = record.expiresAt()

        val remainingTtl = when {
            expiresAt == null -> null
            expiresAt <= now -> 0L
            else -> expiresAt - now
        }
        l1Cache.put(key, value, ttlMs = remainingTtl)
    }

    /**
     * Runs the fetcher, distinguishing "no fetcher" from a genuine failure so that
     * callers can report them differently.
     */
    private suspend fun fetch(key: K, fetcher: (suspend (K) -> V)?): FetchOutcome<V> {
        if (fetcher == null) return FetchOutcome.Absent
        return try {
            FetchOutcome.Success(fetcher(key))
        } catch (e: Exception) {
            FetchOutcome.Failure(e)
        }
    }

    /**
     * Writes a value to both tiers.
     *
     * L2 receives the serialized payload, L1 keeps the live value so that subsequent
     * reads skip serialization entirely.
     */
    private suspend fun write(key: K, value: V, ttlMs: Long?) {
        val createdAt = l1Cache.currentTimeMillis()
        val record = StorageRecord.create(value, serializer, createdAt = createdAt, ttlMillis = ttlMs)
        l2Storage.put(keyToString(key), record)
        l1Cache.put(key, value, ttlMs)
    }

    override suspend fun put(key: K, value: V, ttlMs: Long?) {
        write(key, value, ttlMs)
    }

    /** Writes a value using this cache's [defaultTtlMs]. */
    public suspend fun put(key: K, value: V): Unit = put(key, value, defaultTtlMs)

    override suspend fun invalidate(key: K) {
        l1Cache.remove(key)
        l2Storage.remove(keyToString(key))
    }

    override suspend fun clear() {
        l1Cache.clear()
        l2Storage.clear()
    }

    /** A value resolved from cache, tagged with the tier that produced it. */
    private class Cached<T>(val value: T, val origin: CacheOrigin)

    private companion object {
        private const val NO_FETCHER_MESSAGE: String = "No fetcher provided and cache miss"
        private const val NO_DATA_AFTER_FETCH_MESSAGE: String =
            "Network fetch failed and no cached data available"
    }
}

/** Outcome of invoking a fetcher, separating absence of a fetcher from a real failure. */
private sealed interface FetchOutcome<out T> {
    class Success<T>(val value: T) : FetchOutcome<T>
    class Failure(val cause: Exception) : FetchOutcome<Nothing>
    data object Absent : FetchOutcome<Nothing>
}
