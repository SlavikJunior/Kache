package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.KacheException
import com.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory implementation of [StorageEngine] for testing purposes.
 * Thread-safe and supports all StorageEngine operations.
 */
public class MemoryStorageEngine : StorageEngine {

    private val data = mutableMapOf<String, StorageRecord>()
    private val mutex = Mutex()

    override suspend fun get(key: String): StorageRecord? = mutex.withLock {
        data[key]
    }

    override suspend fun put(key: String, record: StorageRecord) = mutex.withLock {
        data[key] = record
    }

    override suspend fun remove(key: String): Boolean = mutex.withLock {
        data.remove(key) != null
    }

    override suspend fun clear() = mutex.withLock {
        data.clear()
    }

    override suspend fun size(): Long = mutex.withLock {
        data.size.toLong()
    }
}