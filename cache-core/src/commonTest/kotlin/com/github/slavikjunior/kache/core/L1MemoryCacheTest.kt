package com.github.slavikjunior.kache.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class L1MemoryCacheTest {

    @Test
    fun `put and get returns stored value`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 10)

        cache.put("key1", "value1")

        assertEquals("value1", cache.get("key1")?.value)
    }

    @Test
    fun `get returns null for missing key`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 10)

        assertNull(cache.get("missing"))
    }

    @Test
    fun `remove deletes entry`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 10)
        cache.put("key1", "value1")

        cache.remove("key1")

        assertNull(cache.get("key1"))
    }

    @Test
    fun `clear removes all entries`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 10)
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        cache.clear()

        assertNull(cache.get("key1"))
        assertNull(cache.get("key2"))
    }

    @Test
    fun `maxSize must be positive`() {
        assertFailsWith<IllegalArgumentException> { L1MemoryCache<String, String>(maxSize = 0) }
    }

    // --- LRU ---

    @Test
    fun `evicts least recently used entry when over capacity`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 2)
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        assertNull(cache.get("key1"))
        assertEquals("value2", cache.get("key2")?.value)
        assertEquals("value3", cache.get("key3")?.value)
    }

    @Test
    fun `reading an entry protects it from eviction`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 2)
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        cache.get("key1")
        cache.put("key3", "value3")

        assertEquals("value1", cache.get("key1")?.value)
        assertNull(cache.get("key2"))
    }

    @Test
    fun `updating an entry moves it to most recently used`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 2)
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        cache.put("key1", "updated1")
        cache.put("key3", "value3")

        assertEquals("updated1", cache.get("key1")?.value)
        assertNull(cache.get("key2"))
    }

    @Test
    fun `re-putting an existing key does not grow the cache`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 2)
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        repeat(5) { cache.put("key1", "value1") }

        assertEquals(2, cache.size())
    }

    // --- TTL ---

    @Test
    fun `entry expires once its default TTL has passed`() = runTest {
        val timeSource = MutableTimeSource(0)
        val cache = L1MemoryCache<String, String>(maxSize = 10, defaultTtlMs = 100, timeSource = timeSource)
        cache.put("key1", "value1")

        assertEquals("value1", cache.get("key1")?.value)

        timeSource.advance(150)

        assertNull(cache.get("key1"))
    }

    @Test
    fun `per-entry TTL overrides the default`() = runTest {
        val timeSource = MutableTimeSource(0)
        val cache = L1MemoryCache<String, String>(maxSize = 10, defaultTtlMs = 1000, timeSource = timeSource)
        cache.put("short", "value1", ttlMs = 50)
        cache.put("long", "value2", ttlMs = 1000)

        timeSource.advance(100)

        assertNull(cache.get("short"))
        assertEquals("value2", cache.get("long")?.value)
    }

    @Test
    fun `null TTL means the entry never expires`() = runTest {
        val timeSource = MutableTimeSource(0)
        val cache = L1MemoryCache<String, String>(maxSize = 10, defaultTtlMs = 100, timeSource = timeSource)

        cache.put("key1", "value1", ttlMs = null)
        timeSource.advance(150)

        assertEquals("value1", cache.get("key1")?.value)
    }

    @Test
    fun `reading an expired entry evicts it`() = runTest {
        val timeSource = MutableTimeSource(0)
        val cache = L1MemoryCache<String, String>(maxSize = 10, defaultTtlMs = 100, timeSource = timeSource)
        cache.put("key1", "value1")
        timeSource.advance(150)

        assertNull(cache.get("key1"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `record keeps the TTL it was written with`() = runTest {
        val timeSource = MutableTimeSource(0)
        val cache = L1MemoryCache<String, String>(maxSize = 10, timeSource = timeSource)
        cache.put("key1", "value1", ttlMs = 500)

        timeSource.advance(200)
        val record = assertNotNull(cache.get("key1"))

        assertEquals(500, record.ttlMillis)
        assertEquals(0, record.createdAt)
    }

    // --- stale reads ---

    @Test
    fun `getStale returns an expired entry that get would drop`() = runTest {
        val timeSource = MutableTimeSource(0)
        val cache = L1MemoryCache<String, String>(maxSize = 10, defaultTtlMs = 100, timeSource = timeSource)
        cache.put("key1", "value1")
        timeSource.advance(150)

        val record = assertNotNull(cache.getStale("key1"))

        assertEquals("value1", record.value)
        assertTrue(record.isExpired(timeSource.currentTimeMillis()))
        assertEquals(1, cache.size())
    }

    @Test
    fun `getStale marks the entry as most recently used`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 2)
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        cache.getStale("key1")
        cache.put("key3", "value3")

        assertNull(cache.get("key2"))
    }

    // --- remaining TTL ---

    @Test
    fun `getWithTtl reports the full TTL right after writing`() = runTest {
        val timeSource = MutableTimeSource(0)
        val cache = L1MemoryCache<String, String>(maxSize = 10, defaultTtlMs = 1000, timeSource = timeSource)
        cache.put("key1", "value1")

        val entry = assertNotNull(cache.getWithTtl("key1"))

        assertEquals("value1", entry.value)
        assertEquals(1000, entry.remainingTtlMs)
    }

    @Test
    fun `getWithTtl reports what is left of the TTL`() = runTest {
        val timeSource = MutableTimeSource(0)
        val cache = L1MemoryCache<String, String>(maxSize = 10, defaultTtlMs = 1000, timeSource = timeSource)
        cache.put("key1", "value1")

        timeSource.advance(300)

        assertEquals(700, assertNotNull(cache.getWithTtl("key1")).remainingTtlMs)
    }

    @Test
    fun `getWithTtl returns null for an expired entry`() = runTest {
        val timeSource = MutableTimeSource(0)
        val cache = L1MemoryCache<String, String>(maxSize = 10, defaultTtlMs = 50, timeSource = timeSource)
        cache.put("key1", "value1")

        timeSource.advance(100)

        assertNull(cache.getWithTtl("key1"))
    }

    @Test
    fun `getWithTtl reports null for an entry that never expires`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 10)
        cache.put("key1", "value1")

        assertNull(assertNotNull(cache.getWithTtl("key1")).remainingTtlMs)
    }

    // --- housekeeping ---

    @Test
    fun `size reports the entry count`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 10)

        assertEquals(0, cache.size())

        cache.put("key1", "value1")
        cache.put("key2", "value2")
        assertEquals(2, cache.size())

        cache.remove("key1")
        assertEquals(1, cache.size())

        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun `removeExpired drops expired entries and reports how many`() = runTest {
        val timeSource = MutableTimeSource(0)
        val cache = L1MemoryCache<String, String>(maxSize = 10, defaultTtlMs = 100, timeSource = timeSource)
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("forever", "value3", ttlMs = null)

        timeSource.advance(150)
        val removed = cache.removeExpired()

        assertEquals(2, removed)
        assertEquals(1, cache.size())
        assertEquals("value3", cache.get("forever")?.value)
    }

    @Test
    fun `removeExpired keeps entries that have not expired`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 10)
        cache.put("key1", "value1")

        assertEquals(0, cache.removeExpired())
        assertEquals(1, cache.size())
    }

    // --- concurrency ---

    @Test
    fun `interleaved writes keep the cache within capacity`() = runTest {
        val cache = L1MemoryCache<Int, String>(maxSize = 10)

        (0 until 50).map { key ->
            async { cache.put(key, "value$key") }
        }.awaitAll()

        assertEquals(10, cache.size())
    }

    @Test
    fun `interleaved reads of the same key agree on one value`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 10)
        cache.put("key1", "value1")

        val reads = (0 until 20).map { async { cache.get("key1")?.value } }.awaitAll()

        assertTrue(reads.all { it == "value1" })
    }

    // --- time source ---

    @Test
    fun `mutable time source rejects moving backwards`() {
        assertFailsWith<IllegalArgumentException> { MutableTimeSource(0).advance(-1) }
    }

    @Test
    fun `mutable time source can be set to an absolute time`() {
        val timeSource = MutableTimeSource(0)

        timeSource.setTo(1_000)

        assertEquals(1_000, timeSource.currentTimeMillis())
    }
}