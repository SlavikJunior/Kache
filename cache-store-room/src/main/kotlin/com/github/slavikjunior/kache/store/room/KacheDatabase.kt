package com.github.slavikjunior.kache.store.room

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for Kache persistent storage.
 *
 * Contains a single table [CacheEntryEntity] for storing serialized cache entries
 * with TTL metadata.
 */
@Database(
    entities = [CacheEntryEntity::class],
    version = 1,
    exportSchema = true
)
public abstract class KacheDatabase : RoomDatabase() {
    public abstract fun cacheEntryDao(): CacheEntryDao
}
