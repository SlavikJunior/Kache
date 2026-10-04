package io.github.slavikjunior.kache.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * @param maxSize Largest number of L2 records to keep, or null for no limit. Zero and below
 *   also mean no limit. L1 is bounded separately by [L1MemoryCache.maxSize].
 * @param evictionStrategy Which L2 records a write drops first once [maxSize] is exceeded.
 * @param touchGranularity How stale an L2 record's access timestamp may get before a read
 *   refreshes it. Bounds the writes LRU would otherwise make on every read.
 * @param autoReapEvery How often expired records are deleted from both tiers, or null to
 *   never delete them on a schedule. Enabling it starts a coroutine that lives as long as
 *   this cache, so a cache configured this way is expected to be long-lived.
 */
public class ChainKmpCache<K, V>(
    private val l1Cache: L1MemoryCache<K, V>,
    private val l2Storage: StorageEngine,
    private val serializer: KacheSerializer<V>,
    private val keyToString: (K) -> String = { it.toString() },
    private val defaultTtl: Duration? = null,
    private val retryPolicy: RetryPolicy = RetryPolicy.None,
    maxSize: Long? = null,
    evictionStrategy: EvictionStrategy = EvictionStrategy.LRU,
    touchGranularity: Duration = StorageMaintenance.DEFAULT_TOUCH_GRANULARITY,
    private val autoReapEvery: Duration? = null,
) : KmpCache<K, V> {

    private val maintenance = StorageMaintenance(
        storageEngine = l2Storage,
        timeSource = l1Cache.timeSource,
        maxSize = maxSize,
        evictionStrategy = evictionStrategy,
        touchGranularity = touchGranularity,
        autoReapEvery = autoReapEvery,
    )

    private val tier = ChainTier(l1Cache, l2Storage, serializer, keyToString, maintenance)

    private val pipeline = CachePipeline<K, V>(
        tier = tier,
        defaultTtl = defaultTtl,
        retryPolicy = retryPolicy,
    )

    /**
     * Scope for the optional reaper, created only when reaping was asked for.
     *
     * Null whenever `autoReapEvery` is null, which is the default: a cache nobody asked to
     * reap must not leave a coroutine running.
     */
    private val maintenanceScope: CoroutineScope? = startMaintenance()

    private fun startMaintenance(): CoroutineScope? {
        if (autoReapEvery == null) return null

        // SupervisorJob keeps one failed sweep from cancelling the loop; Dispatchers.Default
        // keeps that loop off whichever dispatcher the caller's reads happen to run on.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // L1 is swept alongside L2: its expired entries are otherwise only dropped on read or
        // when capacity forces an eviction.
        maintenance.startAutoReap(scope, memoryTier = { l1Cache.removeExpired() })
        return scope
    }

    override fun get(
        key: K,
        strategy: CacheStrategy,
        fetcher: (suspend (K) -> V)?,
    ): Flow<CacheResult<V>> = pipeline.execute(key, strategy, fetcher)

    override suspend fun put(key: K, value: V, ttl: Duration?) {
        val stringKey = keyToString(key)
        tier.write(key, value, ttl ?: defaultTtl)
        maintenance.enforceCapacity(protectedKey = stringKey)
    }

    override suspend fun invalidate(key: K) {
        tier.remove(key)
    }

    override suspend fun clear() {
        l1Cache.clear()
        l2Storage.clear()
    }

    /**
     * Stops the periodic reaper, if one was started.
     *
     * Has no effect unless `autoReapEvery` was set. The cache stays fully usable afterwards;
     * expired records simply stop being deleted on a schedule.
     */
    public fun stopAutoReap() {
        maintenance.stopAutoReap()
        maintenanceScope?.cancel()
    }
}