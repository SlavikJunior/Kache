package com.github.slavikjunior.kache.store.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a single cache entry in the L2 persistent storage.
 *
 * @property cacheKey Unique key identifying the cached value.
 * @property data Serialized byte array of the cached value.
 * @property createdAt Epoch milliseconds when the entry was stored.
 * @property ttlMillis Time-to-live in milliseconds, or null if the entry never expires.
 */
@Entity(tableName = "cache_entries")
public data class CacheEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "cache_key")
    val cacheKey: String,

    @ColumnInfo(name = "data", typeAffinity = ColumnInfo.BLOB)
    val data: ByteArray,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "ttl_millis")
    val ttlMillis: Long?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as CacheEntryEntity

        if (cacheKey != other.cacheKey) return false
        if (!data.contentEquals(other.data)) return false
        if (createdAt != other.createdAt) return false
        if (ttlMillis != other.ttlMillis) return false

        return true
    }

    override fun hashCode(): Int {
        var result = cacheKey.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (ttlMillis?.hashCode() ?: 0)
        return result
    }
}
