package com.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class L2KmpCacheTest {

    private val timeSource = MutableTimeSource(0)
    private val storage = TestStorageEngine()
    private val serializer = StringTestSerializer()

    private fun cache(
        defaultTtlMs: Long? = null,
        retryPolicy: RetryPolicy = RetryPolicy.None,
    ) = L2KmpCache<String, String>(storage, serializer, defaultTtlMs = defaultTtlMs, timeSource = timeSource, retryPolicy = retryPolicy)

    @Test
    fun `reads back a stored value`() = runTest {
        val cache = cache()
        cache.put("key1", "value1")

        val result = assertIs<CacheResult.Success<String>>(cache.get("key1").first())

        assertEquals("value1", result.data)
        assertEquals(CacheOrigin.DISK, result.origin)
    }

    @Test
    fun `reports a miss for an unknown key`() = runTest {
        val result = cache().get("missing").first()

        assertIs<KacheException.CacheMissException>(assertIs<CacheResult.Error<String>>(result).error)
    }

    @Test
    fun `a value with no TTL never expires`() = runTest {
        val cache = cache()
        cache.put("key1", "value1")

        timeSource.advance(Long.MAX_VALUE / 2)

        assertEquals("value1", assertIs<CacheResult.Success<String>>(cache.get("key1").first()).data)
    }

    @Test
    fun `an expired value is served by StaleWhileRevalidate`() = runTest {
        val cache = cache()
        cache.put("key1", "value1", ttlMs = 100)
        timeSource.advance(150)

        val result = assertIs<CacheResult.Success<String>>(
            cache.get("key1", CacheStrategy.StaleWhileRevalidate) { "fresh" }.first()
        )

        assertEquals("value1", result.data)
        assertEquals(CacheOrigin.DISK_STALE, result.origin)
    }

    @Test
    fun `CacheAndNetwork emits the stored value and then the fetched one`() = runTest {
        val cache = cache()
        cache.put("key1", "cached")

        val results = cache.get("key1", CacheStrategy.CacheAndNetwork) { "network" }.toList()

        assertEquals(listOf("cached", "network"), results.map { (it as CacheResult.Success).data })
    }

    @Test
    fun `NetworkFirst falls back to storage when the fetcher fails`() = runTest {
        val cache = cache()
        cache.put("key1", "cached")

        val result = assertIs<CacheResult.Success<String>>(
            cache.get("key1", CacheStrategy.NetworkFirst) { throw IllegalStateException("down") }.first()
        )

        assertEquals("cached", result.data)
    }

    @Test
    fun `invalidate removes the record`() = runTest {
        val cache = cache()
        cache.put("key1", "value1")

        cache.invalidate("key1")

        assertNull(storage.get("key1"))
        assertIs<CacheResult.Error<String>>(cache.get("key1").first())
    }

    @Test
    fun `clear empties storage`() = runTest {
        val cache = cache()
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        cache.clear()

        assertEquals(0L, storage.size())
    }

    @Test
    fun `a record that cannot be decoded is dropped from storage`() = runTest {
        cache().put("key1", "value1")
        val brokenCache = L2KmpCache<String, String>(storage, FailingSerializer(), timeSource = timeSource)

        val failure = assertThrowsSerializationFailure {
            brokenCache.get("key1").first()
        }

        assertTrue(failure)
        assertNull(storage.get("key1"))
    }

    @Test
    fun `a read failure is reported as a disk read error`() = runTest {
        val brokenCache = L2KmpCache<String, String>(FailingStorageEngine(), serializer, timeSource = timeSource)

        val failure = runCatching { brokenCache.get("key1").first() }.exceptionOrNull()

        assertIs<KacheException.DiskReadException>(failure)
    }

    @Test
    fun `a write failure is reported as a disk write error`() = runTest {
        val brokenCache = L2KmpCache<String, String>(FailingStorageEngine(), serializer, timeSource = timeSource)

        val failure = runCatching { brokenCache.put("key1", "value1") }.exceptionOrNull()

        assertIs<KacheException.DiskWriteException>(failure)
    }

    @Test
    fun `a clear failure is reported as a disk write error`() = runTest {
        val brokenCache = L2KmpCache<String, String>(FailingStorageEngine(), serializer, timeSource = timeSource)

        val failure = runCatching { brokenCache.clear() }.exceptionOrNull()

        assertIs<KacheException.DiskWriteException>(failure)
    }

    /** Runs [block] and reports whether it failed with a serialization error. */
    private suspend fun assertThrowsSerializationFailure(block: suspend () -> Unit): Boolean {
        val failure = runCatching { block() }.exceptionOrNull()
        return failure is KacheException.SerializationException
    }

    /** Backend that fails every operation with a low-level error. */
    private class FailingStorageEngine : StorageEngine {
        private val boom = IllegalStateException("disk on fire")

        override suspend fun get(key: String): StorageRecord<*>? = throw boom

        override suspend fun put(key: String, record: StorageRecord<*>) = throw boom

        override suspend fun remove(key: String): Boolean = throw boom

        override suspend fun clear() = throw boom

        override suspend fun size(): Long = throw boom
    }
}