package io.github.slavikjunior.kache.store.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room DAO for cache entry CRUD operations.
 */
@Dao
public interface CacheEntryDao {

    @Query("SELECT * FROM cache_entries WHERE cache_key = :key")
    public suspend fun get(key: String): CacheEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun put(entity: CacheEntryEntity): Long

    @Query("DELETE FROM cache_entries WHERE cache_key = :key")
    public suspend fun remove(key: String): Int

    @Query("DELETE FROM cache_entries")
    public suspend fun clear(): Int

    @Query("SELECT COUNT(*) FROM cache_entries")
    public suspend fun count(): Long

    @Query("DELETE FROM cache_entries WHERE ttl_millis IS NOT NULL AND (created_at + ttl_millis) < :now")
    public suspend fun removeExpired(now: Long): Int

    @Query("UPDATE cache_entries SET last_accessed_at = :accessedAt WHERE cache_key = :key")
    public suspend fun touch(key: String, accessedAt: Long): Int

    /**
     * Returns the ranking metadata of every row, without their payloads.
     *
     * Selecting only these columns instead of whole entities keeps `data` out of the result
     * set: ranking never looks at the payload, and reading every blob just to decide what to
     * throw away would be wasteful on exactly the caches large enough to need eviction.
     *
     * The rows come back unordered, because which row is worst depends on the caller's
     * [io.github.slavikjunior.kache.core.EvictionStrategy]. Ordering in SQL would hard-code
     * one strategy here and force the others to re-sort a partial result anyway.
     */
    @Query("SELECT cache_key, created_at, ttl_millis, last_accessed_at FROM cache_entries")
    public suspend fun allMetadata(): List<CacheEntryMeta>
}