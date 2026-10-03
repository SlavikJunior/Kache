package io.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * Two-tier cache combining L1 (in-memory) and L2 (persistent) storage.
 *
 * All policy lives in [CachePipeline]; this class is the public facade over it and owns
 * nothing but the wiring between its tiers and the pipeline.
 *
 * @param K Key type.
 * @param V Value type.
 * @param l1Cache In-memory tier. Pass the same [TimeSource] here as to the cache built
 *   on top of it if you override the clock, otherwise the two tiers judge TTL apart.
 * @param l2Storage Persistent tier.
 * @param serializer Serializer used to encode values for L2 and decode them on read.
 * @param keyToString Maps a typed key to the string key used by [l2Storage]. Override
 *   when the default [Any.toString] is not a stable or safe storage key.
 * @param defaultTtl How long entries stay fresh when a write carries no TTL of its own.
 *   Null means entries never expire. A [KmpCache.put] with no [ttl] falls back to
 *   this, so a cache configured with a default cannot store a non-expiring entry through
 *   [put] alone.
 * @param retryPolicy How a failing fetcher is retried. [RetryPolicy.None] by default, so
 *   a fetcher runs exactly once unless retries are asked for.
 */
public class ChainKmpCache<K, V>(
    private val l1Cache: L1MemoryCache<K, V>,
    private val l2Storage: StorageEngine,
    private val serializer: KacheSerializer<V>,
    private val keyToString: (K) -> String = { it.toString() },
    private val defaultTtl: Duration? = null,
    private val retryPolicy: RetryPolicy = RetryPolicy.None,
) : KmpCache<K, V> {

    private val tier = ChainTier(l1Cache, l2Storage, serializer, keyToString)

    private val pipeline = CachePipeline<K, V>(
        tier = tier,
        defaultTtl = defaultTtl,
        retryPolicy = retryPolicy,
    )

    override fun get(
        key: K,
        strategy: CacheStrategy,
        fetcher: (suspend (K) -> V)?,
    ): Flow<CacheResult<V>> = pipeline.execute(key, strategy, fetcher)

    override suspend fun put(key: K, value: V, ttl: Duration?) {
        tier.write(key, value, ttl ?: defaultTtl)
    }

    override suspend fun invalidate(key: K) {
        tier.remove(key)
    }

    override suspend fun clear() {
        l1Cache.clear()
        l2Storage.clear()
    }
}