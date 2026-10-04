package io.github.slavikjunior.kache.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [KacheSerializer] that writes the value's `toString()` as UTF-8.
 *
 * Deliberately not JSON: it keeps the core tests independent of a serialization library
 * and makes the stored payload readable when a test fails.
 */
internal class StringSerializer : KacheSerializer<String> {
    override fun serialize(value: String): ByteArray = value.encodeToByteArray()

    override fun deserialize(bytes: ByteArray): String = bytes.decodeToString()
}

/**
 * Stand-in for a transport failure.
 *
 * Declared here rather than reusing `java.io.IOException`, which does not resolve on
 * Kotlin/Native: these tests run on iOS as well as on the JVM.
 */
internal class TestNetworkException(message: String) : Exception(message)

/** A serializer that always fails, for exercising the error path. */
internal class FailingSerializer : KacheSerializer<String> {
    override fun serialize(value: String): ByteArray =
        throw KacheException.SerializationException(IllegalStateException("serialize failed"))

    override fun deserialize(bytes: ByteArray): String =
        throw KacheException.SerializationException(IllegalStateException("deserialize failed"))
}

/**
 * In-memory [StorageEngine] used as the L2 tier in tests.
 *
 * Records are kept as serialized bytes only, like a real backend, so a test that writes
 * through [put] and reads through [get] exercises serialization in both directions rather
 * than passing the same object reference through.
 */
internal class InMemoryStorageEngine(
    private val serializer: KacheSerializer<*> = StringSerializer(),
) : StorageEngine {

    private val records = mutableMapOf<String, StorageRecord<*>>()
    private val mutex = Mutex()

    /** Number of successful writes, so a test can prove a write reached storage. */
    var writeCount: Int = 0
        private set

    /** Number of times [clear] was called. */
    var clearCount: Int = 0
        private set

    override suspend fun get(key: String): StorageRecord<*>? = mutex.withLock {
        records[key]
    }

    override suspend fun put(key: String, record: StorageRecord<*>) {
        mutex.withLock {
            records[key] = record
            writeCount++
        }
    }

    override suspend fun remove(key: String): Boolean = mutex.withLock {
        records.remove(key) != null
    }

    override suspend fun clear() {
        mutex.withLock {
            records.clear()
            clearCount++
        }
    }

    override suspend fun size(): Long = mutex.withLock { records.size.toLong() }

    override suspend fun removeExpired(now: Long): Long = mutex.withLock {
        val expired = records.filterValues { it.isExpired(now) }.keys.toList()
        expired.forEach { records.remove(it) }
        expired.size.toLong()
    }

    /** Direct access for arranging a state a public API call cannot reach. */
    suspend fun seed(key: String, record: StorageRecord<*>) {
        mutex.withLock { records[key] = record }
    }

    /**
     * A snapshot of what is stored, for asserting on ordering without a real backend.
     *
     * Deliberately returns copies of the map so a test cannot mutate engine state by
     * accident, and so that changing [lastAccessedAt] in a test cannot leak into the cache.
     */
    suspend fun peekRecords(): Map<String, StorageRecord<*>> = mutex.withLock { records.toMap() }
}