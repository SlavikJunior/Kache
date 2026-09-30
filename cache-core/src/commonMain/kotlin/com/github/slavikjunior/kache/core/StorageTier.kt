package com.github.slavikjunior.kache.core

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
 */
internal class StorageTier<K : Any, V : Any>(
    private val storageEngine: StorageEngine,
    private val serializer: KacheSerializer<V>,
    private val keyToString: (K) -> String,
    override val timeSource: TimeSource,
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

        val origin = if (stale) CacheOrigin.DISK_STALE else CacheOrigin.DISK
        return TieredValue(value, origin)
    }

    override suspend fun write(key: K, value: V, ttlMs: Long?) {
        val createdAt = timeSource.currentTimeMillis()
        val record = StorageRecord.create(value, serializer, createdAt, ttlMillis = ttlMs)
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