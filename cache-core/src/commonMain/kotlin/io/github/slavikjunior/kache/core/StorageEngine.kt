package io.github.slavikjunior.kache.core

/**
 * Interface for interacting with the persistent storage backend (L2).
 * Implementations handle the actual I/O (file system, database, key-value store, etc.).
 *
 * The record is exposed as [StorageRecord] with a star projection: L2 is concerned with
 * the serialized [StorageRecord.data] plus its metadata and has no business knowing the
 * caller's value type. Type-specific decoding is the responsibility of the serializer
 * used by [ChainKmpCache].
 *
 * All operations are suspending to allow async I/O and must be thread-safe.
 * Implementations should translate low-level failures into the `KacheException` hierarchy.
 */
public interface StorageEngine {

    /**
     * Retrieves the record stored under [key].
     *
     * Expired records may be returned; deciding what to do with them belongs to the
     * caller, since a stale value can still be useful to some strategies.
     *
     * @param key The key to look up.
     * @return The stored record, or null if no record exists for [key].
     * @throws KacheException.DiskReadException if the storage cannot be read.
     */
    public suspend fun get(key: String): StorageRecord<*>?

    /**
     * Stores [record] under [key], replacing any existing record.
     *
     * @param key The key to store under.
     * @param record The record to persist.
     * @throws KacheException.DiskWriteException if the storage cannot be written.
     */
    public suspend fun put(key: String, record: StorageRecord<*>)

    /**
     * Removes the record stored under [key].
     *
     * @param key The key to remove.
     * @return true if a record was removed, false if the key was absent.
     * @throws KacheException.DiskWriteException if the storage cannot be written.
     */
    public suspend fun remove(key: String): Boolean

    /**
     * Removes every record from storage.
     *
     * @throws KacheException.DiskWriteException if the storage cannot be written.
     */
    public suspend fun clear()

    /**
     * Approximate number of records held.
     *
     * @return The record count, or -1 if the backend cannot report it cheaply.
     * @throws KacheException.DiskReadException if the storage cannot be read.
     */
    public suspend fun size(): Long

    /**
     * Removes all expired records from storage.
     *
     * A record is expired when `createdAt + ttl < now`. Records with null TTL
     * are never expired.
     *
     * Default implementation does nothing and returns 0 (no breaking change for existing backends).
     *
     * @param now Current epoch milliseconds. Implementations should use this value
     *   instead of reading the system clock directly to ensure deterministic behavior in tests.
     * @return The number of records removed, or 0 if the backend does not support this operation.
     * @throws KacheException.DiskWriteException if the storage cannot be written.
     */
    public suspend fun removeExpired(now: Long): Long = 0L

    /**
     * Records the fact that [key] was read at [accessedAt], so that
     * [EvictionStrategy.LRU] and [EvictionStrategy.MRU] can order it correctly.
     *
     * Callers must not invoke this on every read: writing on each read would turn
     * cache hits into storage writes. The contract is that the caller only touches a key
     * whose stored access timestamp is already older than a granularity window, which
     * bounds the writes to at most one per key per window.
     *
     * Default implementation does nothing and returns false, so backends that cannot
     * track access cheaply keep working — they simply fall back to the created-at-based
     * strategies, since a record's access timestamp then always equals its creation one.
     *
     * @param key The key that was read.
     * @param accessedAt Current epoch milliseconds.
     * @return true if the timestamp was persisted, false if the backend does not support this.
     * @throws KacheException.DiskWriteException if the storage cannot be written.
     */
    public suspend fun touch(key: String, accessedAt: Long): Boolean = false

    /**
     * Returns up to [limit] keys that [strategy] ranks as the ones to drop next, worst
     * candidate first.
     *
     * Expired records must come before anything the strategy ranks, because an expired
     * record cannot be served and dropping it costs nothing. Implementations that cannot
     * enumerate their records cheaply should return an empty list rather than guess.
     *
     * @param strategy The ordering to apply.
     * @param limit Maximum number of keys to return. Implementations may return fewer.
     * @param now Current epoch milliseconds, used to rank expired records first.
     * @return Candidate keys in eviction order, or an empty list if unsupported.
     * @throws KacheException.DiskReadException if the storage cannot be read.
     */
    public suspend fun evictionCandidates(
        strategy: EvictionStrategy,
        limit: Int,
        now: Long,
    ): List<String> = emptyList()
}
