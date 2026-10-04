package io.github.slavikjunior.kache.store.room

import androidx.room.ColumnInfo

/**
 * The columns [RoomStorageEngine] needs to rank a cache entry for eviction, without its
 * payload.
 *
 * Ranking exists to decide what to throw away, so it never needs the stored bytes. Selecting
 * these four columns instead of whole [CacheEntryEntity] rows keeps a multi-megabyte payload
 * out of the result set on the caches that are large enough to be evicting in the first place.
 *
 * Public because it appears in the signature of a public DAO method.
 *
 * @property cacheKey The entry's key.
 * @property createdAt Epoch milliseconds when the entry was stored.
 * @property ttlMillis Time-to-live in milliseconds, or null if the entry never expires.
 * @property lastAccessedAt Epoch milliseconds of the last read.
 */
public data class CacheEntryMeta(
    @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "ttl_millis") val ttlMillis: Long?,
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long,
)
