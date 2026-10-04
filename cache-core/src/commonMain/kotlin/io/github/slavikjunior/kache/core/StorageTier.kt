package io.github.slavikjunior.kache.core

import kotlin.time.Duration

/**
 * A single persistent tier: values are serialized into a [StorageEngine] and decoded on
 * the way back out.
 *
 * @param K Key type.
 * @param V Value type.
 * @param storageEngine Persistent backend holding the records.
 * @param serializer Serializer used to encode values on write and decode them on read.
 * @param keyToString Maps a typed key to the string key used by [storageEngine]. Override
 *   when the default [Any.toString] is not stable or safe as a storage key.
 * @param timeSource Clock used to decide whether a record has expired.
 * @param maintenance Access tracking, applied after a successful read. Optional: without it
 *   a backend never learns that a record was used, and LRU ordering stays write-ordered.
 */
internal class StorageTier<K : Any, V : Any>(
    private val storageEngine: StorageEngine,
    private val serializer: KacheSerializer<V>,
    private val keyToString: (K) -> String,
    override val timeSource: TimeSource,
    private val maintenance: StorageMaintenance? = null,
) : CacheTier<K, V> {

    /**
     * Reads a value from storage.
     *
     * A record that fails to decode is removed, since leaving it in place would make
     * every subsequent read fail the same way.
     */
    override suspend fun read(key: K, allowStale: Boolean): TieredValue<V>? {
        val stringKey = keyToString(key)

        val record = try {
            storageEngine.get(stringKey)
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            throw KacheException.DiskReadException(e)
        } ?: return null

        val stale = record.isExpired(timeSource.currentTimeMillis())
        if (stale && !allowStale) return null

        val value = try {
            serializer.deserialize(record.data)
        } catch (e: Exception) {
            storageEngine.remove(stringKey)
            throw if (e is KacheException.SerializationException) e else KacheException.SerializationException(e)
        }

        // Recorded after the value is known good, so a failed decode does not make a record
        // look used.
        maintenance?.touchIfNeeded(stringKey, record)

        val origin = if (stale) CacheOrigin.DISK_STALE else CacheOrigin.DISK
        return TieredValue(value, origin)
    }

    override suspend fun write(key: K, value: V, ttl: Duration?) {
        val createdAt = timeSource.currentTimeMillis()
        val record = StorageRecord.create(value, serializer, createdAt, ttl = ttl)
        writeRecord { storageEngine.put(keyToString(key), record) }
    }

    override suspend fun remove(key: K) {
        writeRecord { storageEngine.remove(keyToString(key)) }
    }

    /** Runs a backend mutation, translating any low-level failure into a typed one. */
    private suspend fun writeRecord(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(e)
        }
    }
}
