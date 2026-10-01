package io.github.slavikjunior.kache.store.room

import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.StorageEngine
import io.github.slavikjunior.kache.core.StorageRecord

/**
 * [StorageEngine] implementation backed by Room KMP database.
 *
 * Provides ACID-compliant persistent storage for cache entries with TTL support.
 * Expired records are automatically cleaned up via [removeExpired].
 *
 * @property database The Room database instance.
 */
public class RoomStorageEngine internal constructor(
    private val database: KacheDatabase
) : StorageEngine {

    private val dao: CacheEntryDao = database.cacheEntryDao()

    public override suspend fun get(key: String): StorageRecord<*>? {
        return try {
            val entity = dao.get(key) ?: return null
            StorageRecord<ByteArray>(
                value = entity.data,
                data = entity.data,
                createdAt = entity.createdAt,
                ttlMillis = entity.ttlMillis
            )
        } catch (e: Exception) {
            throw KacheException.DiskReadException(cause = e)
        }
    }

    public override suspend fun put(key: String, record: StorageRecord<*>) {
        try {
            val entity = CacheEntryEntity(
                cacheKey = key,
                data = record.data,
                createdAt = record.createdAt,
                ttlMillis = record.ttlMillis
            )
            dao.put(entity)
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(cause = e)
        }
    }

    public override suspend fun remove(key: String): Boolean {
        return try {
            dao.remove(key) > 0
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(cause = e)
        }
    }

    public override suspend fun clear() {
        try {
            dao.clear()
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(cause = e)
        }
    }

    public override suspend fun size(): Long {
        return try {
            dao.count()
        } catch (e: Exception) {
            throw KacheException.DiskReadException(cause = e)
        }
    }

    public override suspend fun removeExpired(now: Long): Long {
        return try {
            dao.removeExpired(now).toLong()
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(cause = e)
        }
    }
}
