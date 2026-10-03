package io.github.slavikjunior.kache.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The two-tier combination of an in-memory cache in front of a persistent [StorageEngine].
 *
 * A read is served from the cheapest tier that holds usable data:
 * 1. L1 memory hit yields [CacheOrigin.MEMORY].
 * 2. L1 miss falls through to L2; a hit is promoted into L1 and yields [CacheOrigin.DISK].
 *
 * Promotion preserves the original creation time of the L2 record, so moving a value
 * between tiers never extends its lifetime.
 *
 * The clock is L1's: L1 owns a record once it is promoted and judges its freshness
 * itself, so one clock covering both tiers is what keeps them consistent.
 *
 * @param K Key type.
 * @param V Value type.
 * @param l1Cache In-memory tier.
 * @param l2Storage Persistent tier.
 * @param serializer Serializer used to encode values for L2 and decode them on read.
 * @param keyToString Maps a typed key to the string key used by [l2Storage]. Override
 *   when the default [Any.toString] is not a stable or safe storage key.
 */
internal class ChainTier<K, V>(
    private val l1Cache: L1MemoryCache<K, V>,
    private val l2Storage: StorageEngine,
    private val serializer: KacheSerializer<V>,
    private val keyToString: (K) -> String,
) : CacheTier<K, V> {

    override val timeSource: TimeSource get() = l1Cache.timeSource

    /**
     * Resolves a value, promoting from L2 into L1 on an L1 miss.
     *
     * @param allowStale When true, expired records are returned and retained. When
     *   false, an expired record is discarded and reported as a miss.
     * @return The value and the tier it came from, or null on a miss.
     */
    override suspend fun read(key: K, allowStale: Boolean): TieredValue<V>? {
        val now = timeSource.currentTimeMillis()

        // getStale is used when the caller can tolerate expired data, so the entry is
        // not evicted before the strategy has had a chance to serve it.
        val l1Record = if (allowStale) l1Cache.getStale(key) else l1Cache.get(key)
        if (l1Record != null) {
            val stale = l1Record.isExpired(now)
            val origin = if (stale) CacheOrigin.MEMORY_STALE else CacheOrigin.MEMORY
            return TieredValue(l1Record.value, origin)
        }

        val l2Record = l2Storage.get(keyToString(key)) ?: return null
        val stale = l2Record.isExpired(now)
        if (stale && !allowStale) return null

        val value = serializer.deserialize(l2Record.data)
        promoteToL1(key, value, l2Record)

        val origin = if (stale) CacheOrigin.DISK_STALE else CacheOrigin.DISK
        return TieredValue(value, origin)
    }

    /**
     * Writes a value to both tiers.
     *
     * L2 receives the serialized payload, L1 keeps the live value so that subsequent
     * reads skip serialization entirely.
     */
    override suspend fun write(key: K, value: V, ttl: Duration?) {
        val createdAt = timeSource.currentTimeMillis()
        val record = StorageRecord.create(value, serializer, createdAt = createdAt, ttl = ttl)
        l2Storage.put(keyToString(key), record)
        l1Cache.put(key, value, ttl)
    }

    override suspend fun remove(key: K) {
        l1Cache.remove(key)
        l2Storage.remove(keyToString(key))
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
        val now = timeSource.currentTimeMillis()
        val expiresAt = record.expiresAt()

        val remainingTtl = when {
            expiresAt == null -> null
            expiresAt <= now -> Duration.ZERO
            else -> (expiresAt - now).milliseconds
        }
        l1Cache.put(key, value, ttl = remainingTtl)
    }
}