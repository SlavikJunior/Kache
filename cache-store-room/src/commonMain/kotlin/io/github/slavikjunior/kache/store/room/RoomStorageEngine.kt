package io.github.slavikjunior.kache.store.room

import kotlin.time.Duration.Companion.milliseconds

import io.github.slavikjunior.kache.core.EvictionStrategy
import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.StorageEngine
import io.github.slavikjunior.kache.core.StorageRecord
import io.github.slavikjunior.kache.core.recordComparator

/**
 * [StorageEngine] implementation backed by Room KMP database.
 *
 * Provides ACID-compliant persistent storage for cache entries with TTL support.
 *
 * Expired rows are not removed on their own. Reading a key returns its row even when stale,
 * because whether a stale value is still useful belongs to the caching strategy above; a row
 * is deleted when [removeExpired] is called, which
 * [L2KmpCache][io.github.slavikjunior.kache.core.L2KmpCache] can be configured to do on a
 * schedule.
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
                ttl = entity.ttlMillis?.milliseconds,
                lastAccessedAt = entity.lastAccessedAt,
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
                // The column stays in milliseconds on purpose: it is part of the database
                // schema, and changing it would force a migration on every consumer.
                ttlMillis = record.ttl?.inWholeMilliseconds,
                lastAccessedAt = record.lastAccessedAt,
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

    /**
     * Deletes every expired record and reports how many went.
     *
     * Nothing calls this on a schedule by itself: expired rows are only removed when a caller
     * asks. [L2KmpCache][io.github.slavikjunior.kache.core.L2KmpCache] can be configured to
     * do so periodically, which is opt-in precisely because it costs a background write.
     */
    public override suspend fun removeExpired(now: Long): Long {
        return try {
            dao.removeExpired(now).toLong()
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(cause = e)
        }
    }

    public override suspend fun touch(key: String, accessedAt: Long): Boolean {
        return try {
            dao.touch(key, accessedAt) > 0
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(cause = e)
        }
    }

    /**
     * Ranks stored entries by [strategy], expired ones first.
     *
     * Reads metadata only, without the payloads, and sorts in Kotlin so that all four
     * strategies share one implementation with the file backend.
     */
    public override suspend fun evictionCandidates(
        strategy: EvictionStrategy,
        limit: Int,
        now: Long,
    ): List<String> {
        if (limit <= 0) return emptyList()

        return try {
            val byStrategy = strategy.recordComparator()
            val comparator = Comparator<Pair<CacheEntryMeta, StorageRecord<ByteArray>>> { left, right ->
                byStrategy.compare(left.second, right.second)
            }

            dao.allMetadata()
                .map { meta ->
                    meta to StorageRecord(
                        value = ByteArray(0),
                        data = ByteArray(0),
                        createdAt = meta.createdAt,
                        ttl = meta.ttlMillis?.milliseconds,
                        lastAccessedAt = meta.lastAccessedAt,
                    )
                }
                .sortedWith(
                    // Expired rows first: none can be served again, so dropping one costs
                    // nothing regardless of what the strategy would have ranked.
                    compareByDescending<Pair<CacheEntryMeta, StorageRecord<ByteArray>>> {
                        it.second.isExpired(now)
                    }.then(comparator),
                )
                .take(limit)
                .map { (meta, _) -> meta.cacheKey }
        } catch (e: Exception) {
            throw KacheException.DiskReadException(cause = e)
        }
    }
}
