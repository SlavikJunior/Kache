package io.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * A single-tier cache built directly on a [StorageEngine], with no in-memory tier.
 *
 * Use this when only durable storage is wanted, or to test cache behaviour against a
 * real backend without a memory tier in the way. When an in-memory tier should sit in
 * front of the backend, use [ChainKmpCache] instead, which shares the same
 * [CachePipeline] over L1 plus L2.
 *
 * @param K Key type. Must be convertible to a stable string via [keyToString].
 * @param V Value type.
 * @param storageEngine Persistent backend holding the records.
 * @param valueSerializer Serializer used to encode values on write and decode them on read.
 * @param keyToString Maps a typed key to the string key used by [storageEngine]. Override
 *   when the default [Any.toString] is not stable or safe as a storage key.
 * @param defaultTtl How long entries stay fresh when a write carries no TTL of its own.
 *   Null means entries never expire.
 * @param timeSource Clock used to decide whether a record has expired. Inject a
 *   [MutableTimeSource] to test expiry without waiting.
 * @param retryPolicy How a failing fetcher is retried. [RetryPolicy.None] by default, so
 *   a fetcher runs exactly once unless retries are asked for.
 */
public class L2KmpCache<K : Any, V : Any>(
    private val storageEngine: StorageEngine,
    private val valueSerializer: KacheSerializer<V>,
    private val keyToString: (K) -> String = { it.toString() },
    private val defaultTtl: Duration? = null,
    timeSource: TimeSource = SystemTimeSource,
    private val retryPolicy: RetryPolicy = RetryPolicy.None,
) : KmpCache<K, V> {

    private val tier = StorageTier(storageEngine, valueSerializer, keyToString, timeSource)

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
        try {
            storageEngine.clear()
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(e)
        }
    }
}