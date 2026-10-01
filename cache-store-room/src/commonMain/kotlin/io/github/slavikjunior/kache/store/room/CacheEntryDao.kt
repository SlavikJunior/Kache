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
}