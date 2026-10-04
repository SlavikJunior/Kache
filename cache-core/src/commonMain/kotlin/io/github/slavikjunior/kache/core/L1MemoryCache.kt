package io.github.slavikjunior.kache.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Thread-safe in-memory cache with expired-first eviction and optional TTL.
 *
 * All state transitions happen under a coroutines [Mutex], which is the only
 * synchronization primitive available in `commonMain` across all KMP targets.
 *
 * @param K Key type.
 * @param V Value type.
 * @param maxSize Maximum number of entries before eviction triggers. Must be positive.
 * @param defaultTtl How long entries stay fresh when [put] is called without a TTL of its
 *   own. Null means entries do not expire.
 * @param timeSource Clock used for TTL decisions. Inject [MutableTimeSource] in tests,
 *   or pass a source shared with the cache pipeline to keep both tiers on one clock.
 *
 * @throws IllegalArgumentException if [maxSize] is not positive.
 */
public class L1MemoryCache<K, V>(
    private val maxSize: Int,
    private val defaultTtl: Duration? = null,
    internal val timeSource: TimeSource = SystemTimeSource,
) {
    init {
        require(maxSize > 0) { "maxSize must be positive, but was $maxSize" }
    }

    private val entries = mutableMapOf<K, Entry<V>>()

    /** Keys ordered from least to most recently used. Head is the eviction candidate. */
    private val accessOrder = mutableListOf<K>()

    private val mutex = Mutex()

    /**
     * Returns the record for [key] if it is present and not expired.
     *
     * An expired entry is evicted as a side effect of this call, so a null return
     * means "no usable fresh data" and never "stale data is available".
     * Use [getStale] when stale values must be served.
     *
     * Marks the entry as most recently used.
     *
     * @param key The key to read.
     * @return The record, or null if absent or expired.
     */
    public suspend fun get(key: K): StorageRecord<V>? = mutex.withLock {
        val now = timeSource.currentTimeMillis()
        val entry = entries[key] ?: return@withLock null

        if (entry.isExpired(now)) {
            removeEntry(key)
            return@withLock null
        }

        markAsMostRecentlyUsed(key)
        entry.toRecord()
    }

    /**
     * Returns the record for [key] regardless of expiration, without evicting it.
     *
     * This exists for [CacheStrategy.StaleWhileRevalidate], which must be able to
     * serve a value that has already outlived its TTL while a refresh is in flight.
     * Callers are responsible for checking [StorageRecord.isExpired] to decide
     * whether the value they got is stale.
     *
     * Marks the entry as most recently used.
     *
     * @param key The key to read.
     * @return The record, or null if absent.
     */
    public suspend fun getStale(key: K): StorageRecord<V>? = mutex.withLock {
        val entry = entries[key] ?: return@withLock null
        markAsMostRecentlyUsed(key)
        entry.toRecord()
    }

    /**
     * Returns the value for [key] along with how much of its TTL is left.
     *
     * Evicts the entry if expired. Prefer [get] when the remaining TTL is not needed.
     *
     * @param key The key to read.
     * @return The value and its remaining TTL, or null if absent or expired.
     *   A null [L1CacheEntry.remainingTtl] means the entry never expires.
     */
    internal suspend fun getWithTtl(key: K): L1CacheEntry<V>? = mutex.withLock {
        val now = timeSource.currentTimeMillis()
        val entry = entries[key] ?: return@withLock null

        if (entry.isExpired(now)) {
            removeEntry(key)
            return@withLock null
        }

        markAsMostRecentlyUsed(key)
        L1CacheEntry(entry.value, entry.remainingTtl(now))
    }

    /**
     * Stores [value] under [key], resetting any existing TTL.
     *
     * Evicts one entry if this pushes the cache over [maxSize]: an expired one when
     * any exists, otherwise the least recently used.
     *
     * @param key The key to write.
     * @param value The value to store.
     * @param ttl How long the entry stays fresh, or null to use [defaultTtl].
     */
    public suspend fun put(key: K, value: V, ttl: Duration? = defaultTtl): Unit = mutex.withLock {
        val now = timeSource.currentTimeMillis()
        // Kept as epoch millis internally: every comparison here is between timestamps,
        // so converting once on the way in is cheaper than converting on each check.
        val expiresAt = ttl?.let { now + it.inWholeMilliseconds }

        val isUpdate = entries.containsKey(key)
        entries[key] = Entry(value = value, createdAt = now, expiresAt = expiresAt)

        if (isUpdate) {
            accessOrder.remove(key)
        }
        accessOrder.add(key)

        if (entries.size > maxSize) {
            evictOne(now)
        }
    }

    /**
     * Removes the entry for [key] if present.
     *
     * @param key The key to remove.
     */
    public suspend fun remove(key: K): Unit = mutex.withLock {
        removeEntry(key)
    }

    /**
     * Removes every entry.
     */
    public suspend fun clear(): Unit = mutex.withLock {
        entries.clear()
        accessOrder.clear()
    }

    /**
     * Number of entries currently held, including any that are expired but not yet reaped.
     *
     * @return The entry count.
     */
    public suspend fun size(): Int = mutex.withLock { entries.size }

    /**
     * Drops every expired entry.
     *
     * Expired entries are otherwise only reaped on read, so a key that is written
     * once and never read again would occupy capacity indefinitely.
     *
     * @return How many entries were removed.
     */
    public suspend fun removeExpired(): Int = mutex.withLock {
        val now = timeSource.currentTimeMillis()
        val expiredKeys = entries.filterValues { it.isExpired(now) }.keys.toList()
        expiredKeys.forEach { removeEntry(it) }
        expiredKeys.size
    }

    private fun removeEntry(key: K) {
        entries.remove(key)
        accessOrder.remove(key)
    }

    private fun markAsMostRecentlyUsed(key: K) {
        accessOrder.remove(key)
        accessOrder.add(key)
    }

    /**
     * Drops a single entry once the cache is over capacity.
     *
     * An expired entry is always preferred over the least recently used one. Evicting
     * an expired entry costs nothing, because no caller can ever read it back through
     * [get]; evicting a fresh one does. Falls back to plain LRU when nothing has
     * expired yet.
     *
     * @param now Current epoch millis, taken once by the caller.
     */
    private fun evictOne(now: Long) {
        val expiredKey = entries.entries.firstOrNull { it.value.isExpired(now) }?.key
        removeEntry(expiredKey ?: accessOrder.first())
    }

    /**
     * Internal record shape. Kept private so that TTL bookkeeping cannot be
     * constructed or mutated from outside the cache.
     */
    private class Entry<V>(
        val value: V,
        val createdAt: Long,
        val expiresAt: Long?,
    ) {
        fun isExpired(now: Long): Boolean = expiresAt?.let { now > it } ?: false

        fun remainingTtl(now: Long): Duration? = expiresAt?.let { (it - now).milliseconds }

        fun toRecord(): StorageRecord<V> = StorageRecord(
            value = value,
            createdAt = createdAt,
            ttl = expiresAt?.let { (it - createdAt).milliseconds },
        )
    }

    /**
     * A value paired with its remaining TTL.
     *
     * @param value The cached value.
     * @param remainingTtl How long until expiration, or null if the entry never expires.
     */
    internal data class L1CacheEntry<V>(
        val value: V,
        val remainingTtl: Duration?,
    )
}
