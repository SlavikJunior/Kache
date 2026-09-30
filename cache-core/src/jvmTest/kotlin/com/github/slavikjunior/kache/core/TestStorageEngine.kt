package com.github.slavikjunior.kache.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory implementation of [StorageEngine] for testing purposes.
 * Thread-safe and supports all StorageEngine operations.
 */
internal class TestStorageEngine : StorageEngine {

    private val data = mutableMapOf<String, StorageRecord<*>>()
    private val mutex = Mutex()

    override suspend fun get(key: String): StorageRecord<*>? = mutex.withLock {
        data[key]
    }

    override suspend fun put(key: String, record: StorageRecord<*>) = mutex.withLock {
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
