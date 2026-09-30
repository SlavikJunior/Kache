package com.github.slavikjunior.kache.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [StorageEngine] used as a stand-in for a persistent backend.
 *
 * Records are kept exactly as the cache hands them over, so a test can inspect what was
 * written without going through a serializer.
 */
internal class TestStorageEngine : StorageEngine {

    private val records = mutableMapOf<String, StorageRecord<*>>()
    private val mutex = Mutex()

    override suspend fun get(key: String): StorageRecord<*>? = mutex.withLock {
        records[key]
    }

    override suspend fun put(key: String, record: StorageRecord<*>) = mutex.withLock {
        records[key] = record
    }

    override suspend fun remove(key: String): Boolean = mutex.withLock {
        records.remove(key) != null
    }

    override suspend fun clear() = mutex.withLock {
        records.clear()
    }

    override suspend fun size(): Long = mutex.withLock {
        records.size.toLong()
    }

    /** Number of stored records, without going through the suspending [size]. */
    fun sizeUnsafe(): Int = records.size
}

/** [KacheSerializer] that stores strings as their UTF-8 bytes. */
internal class StringTestSerializer : KacheSerializer<String> {
    override fun serialize(value: String): ByteArray = value.encodeToByteArray()

    override fun deserialize(bytes: ByteArray): String = bytes.decodeToString()
}

/** [KacheSerializer] that fails on every call, for exercising serialization errors. */
internal class FailingSerializer : KacheSerializer<String> {
    override fun serialize(value: String): ByteArray =
        throw KacheException.SerializationException(IllegalArgumentException("serialize failed"))

    override fun deserialize(bytes: ByteArray): String =
        throw KacheException.SerializationException(IllegalArgumentException("deserialize failed"))
}