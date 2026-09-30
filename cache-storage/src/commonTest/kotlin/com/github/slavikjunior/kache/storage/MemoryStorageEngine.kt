package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.StorageEngine
import com.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [StorageEngine] used as a test double for the persistent tier.
 *
 * Records are stored by reference rather than as they would be on disk, so a test that
 * needs to observe what the cache really wrote has to go through the codec itself.
 * This is sufficient for exercising strategy logic, which is what the common tests
 * use it for.
 *
 * Thread-safe, mirroring the guarantees a real backend is expected to provide.
 */
internal class MemoryStorageEngine : StorageEngine {

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
}
