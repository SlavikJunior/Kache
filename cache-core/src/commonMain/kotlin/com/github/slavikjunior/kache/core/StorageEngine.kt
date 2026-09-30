package com.github.slavikjunior.kache.core

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
     * A record is expired when `createdAt + ttlMillis < now`. Records with null TTL
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
}
