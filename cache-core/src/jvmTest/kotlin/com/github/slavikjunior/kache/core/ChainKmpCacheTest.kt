package com.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChainKmpCacheTest {

    private fun createCache(timeSource: L1MemoryCache.TimeSource = L1MemoryCache.SystemTimeSource): ChainKmpCache<String, String> {
        val l1 = L1MemoryCache<String, String>(maxSize = 10, timeSource = timeSource)
        val l2 = TestStorageEngine()
        val serializer = StringTestSerializer()
        return ChainKmpCache(l1, l2, serializer)
    }

    @Test
    fun `CacheFirst returns from L1 on hit`() = runTest {
        val cache = createCache()
        cache.l1Cache.put("key1", "l1-value")
        
        val result = cache.get("key1", CacheStrategy.CacheFirst, null).first()
        
        val success = result as CacheResult.Success<String>
        assertEquals("l1-value", success.data)
        assertEquals(CacheOrigin.MEMORY, success.origin)
    }

    @Test
    fun `CacheFirst returns from L2 on L1 miss`() = runTest {
        val cache = createCache()
        // Put directly in L2
        val record = StorageRecord.create("l2-value", StringTestSerializer(), createdAt = 0L)
        cache.l2Storage.put("key1", record)
        
        val result = cache.get("key1", CacheStrategy.CacheFirst, null).first()
        
        val success = result as CacheResult.Success<String>
        assertEquals("l2-value", success.data)
        assertEquals(CacheOrigin.DISK, success.origin)
        
        // Verify promoted to L1
        assertEquals("l2-value", cache.l1Cache.get("key1")?.value)
    }

    @Test
    fun `CacheFirst calls fetcher on cache miss`() = runTest {
        val cache = createCache()
        var fetchCalled = false
        
        val result = cache.get("key1", CacheStrategy.CacheFirst) {
            fetchCalled = true
            "network-value"
        }.first()
        
        val success = result as CacheResult.Success<String>
        assertEquals("network-value", success.data)
        assertEquals(CacheOrigin.NETWORK, success.origin)
        assertTrue(fetchCalled)
        
        // Verify stored in both L1 and L2
        assertEquals("network-value", cache.l1Cache.get("key1")?.value)
        val l2Record = cache.l2Storage.get("key1")
        assertTrue(l2Record != null)
    }

    @Test
    fun `NetworkFirst tries network first`() = runTest {
        val cache = createCache()
        var fetchCalled = false
        
        val result = cache.get("key1", CacheStrategy.NetworkFirst) {
            fetchCalled = true
            "network-value"
        }.first()
        
        val success = result as CacheResult.Success<String>
        assertEquals("network-value", success.data)
        assertEquals(CacheOrigin.NETWORK, success.origin)
        assertTrue(fetchCalled)
    }

    @Test
    fun `NetworkFirst falls back to L1 on network failure`() = runTest {
        val cache = createCache()
        cache.l1Cache.put("key1", "l1-value")
        
        val result = cache.get("key1", CacheStrategy.NetworkFirst) {
            throw IllegalStateException("network down")
        }.first()
        
        val success = result as CacheResult.Success<String>
        assertEquals("l1-value", success.data)
        assertEquals(CacheOrigin.MEMORY, success.origin)
    }

    @Test
    fun `NetworkFirst falls back to L2 on network failure`() = runTest {
        val cache = createCache()
        val record = StorageRecord.create("l2-value", StringTestSerializer(), createdAt = 0L)
        cache.l2Storage.put("key1", record)
        
        val result = cache.get("key1", CacheStrategy.NetworkFirst) {
            throw IllegalStateException("network down")
        }.first()
        
        val success = result as CacheResult.Success<String>
        assertEquals("l2-value", success.data)
        assertEquals(CacheOrigin.DISK, success.origin)
    }

    @Test
    fun `CacheAndNetwork emits both cache and network`() = runTest {
        val cache = createCache()
        cache.l1Cache.put("key1", "l1-value")
        var fetchCalled = false
        
        val results = cache.get("key1", CacheStrategy.CacheAndNetwork) {
            fetchCalled = true
            "network-value"
        }.toList()
        
        assertEquals(2, results.size)
        val success1 = results[0] as CacheResult.Success<String>
        assertEquals("l1-value", success1.data)
        assertEquals(CacheOrigin.MEMORY, success1.origin)
        
        val success2 = results[1] as CacheResult.Success<String>
        assertEquals("network-value", success2.data)
        assertEquals(CacheOrigin.NETWORK, success2.origin)
        assertTrue(fetchCalled)
    }

    @Test
    fun `CacheAndNetwork emits only network if no cache`() = runTest {
        val cache = createCache()
        var fetchCalled = false
        
        val results = cache.get("key1", CacheStrategy.CacheAndNetwork) {
            fetchCalled = true
            "network-value"
        }.toList()
        
        assertEquals(1, results.size)
        val success = results[0] as CacheResult.Success<String>
        assertEquals("network-value", success.data)
        assertEquals(CacheOrigin.NETWORK, success.origin)
        assertTrue(fetchCalled)
    }

    @Test
    fun `StaleWhileRevalidate emits stale from L1`() = runTest {
        val timeSource = FakeTimeSource(0)
        val cache = createCache(timeSource)
        // Put stale entry in L1 (TTL = 1ms, advance time)
        cache.l1Cache.put("key1", "stale-value", ttlMs = 1)
        timeSource.advance(10)
        
        var fetchCalled = false
        val results = cache.get("key1", CacheStrategy.StaleWhileRevalidate) {
            fetchCalled = true
            "fresh-value"
        }.toList()
        
        assertEquals(2, results.size)
        // First should be stale from L1
        val stale = results[0] as CacheResult.Success<String>
        assertEquals("stale-value", stale.data)
        assertEquals(CacheOrigin.MEMORY_STALE, stale.origin)
        
        // Second should be fresh from network
        val fresh = results[1] as CacheResult.Success<String>
        assertEquals("fresh-value", fresh.data)
        assertEquals(CacheOrigin.NETWORK, fresh.origin)
        assertTrue(fetchCalled)
    }

    @Test
    fun `StaleWhileRevalidate emits stale from L2`() = runTest {
        val timeSource = FakeTimeSource(0)
        val cache = createCache(timeSource)
        // Put stale entry in L2
        val record = StorageRecord.create("stale-value", StringTestSerializer(), createdAt = 0L, ttlMillis = 1)
        cache.l2Storage.put("key1", record)
        timeSource.advance(10)
        
        var fetchCalled = false
        val results = cache.get("key1", CacheStrategy.StaleWhileRevalidate) {
            fetchCalled = true
            "fresh-value"
        }.toList()
        
        assertEquals(2, results.size)
        // First should be stale from L2
        val stale = results[0] as CacheResult.Success<String>
        assertEquals("stale-value", stale.data)
        assertEquals(CacheOrigin.DISK_STALE, stale.origin)
        
        // Second should be fresh from network
        val fresh = results[1] as CacheResult.Success<String>
        assertEquals("fresh-value", fresh.data)
        assertEquals(CacheOrigin.NETWORK, fresh.origin)
        assertTrue(fetchCalled)
    }

    @Test
    fun `put stores in both L1 and L2`() = runTest {
        val cache = createCache()
        cache.put("key1", "new-value", null)
        
        assertEquals("new-value", cache.l1Cache.get("key1")?.value)
        val l2Record = cache.l2Storage.get("key1")
        assertTrue(l2Record != null)
        assertEquals("new-value", StringTestSerializer().deserialize(l2Record.data))
    }

    @Test
    fun `invalidate removes from both L1 and L2`() = runTest {
        val cache = createCache()
        cache.put("key1", "value1", null)
        
        cache.invalidate("key1")
        
        assertNull(cache.l1Cache.get("key1"))
        assertNull(cache.l2Storage.get("key1"))
    }

    @Test
    fun `clear removes all from both L1 and L2`() = runTest {
        val cache = createCache()
        cache.put("key1", "value1", null)
        cache.put("key2", "value2", null)
        
        cache.clear()
        
        assertNull(cache.l1Cache.get("key1"))
        assertNull(cache.l1Cache.get("key2"))
        assertNull(cache.l2Storage.get("key1"))
        assertNull(cache.l2Storage.get("key2"))
    }

    @Test
    fun `L1 warming from L2 on CacheFirst`() = runTest {
        val cache = createCache()
        val record = StorageRecord.create("l2-value", StringTestSerializer(), createdAt = 0L)
        cache.l2Storage.put("key1", record)
        
        val result = cache.get("key1", CacheStrategy.CacheFirst, null).first()
        
        val success = result as CacheResult.Success<String>
        assertEquals("l2-value", success.data)
        
        // Verify L1 warmed
        assertEquals("l2-value", cache.l1Cache.get("key1")?.value)
    }

    @Test
    fun `L2 and L1 warming from network`() = runTest {
        val cache = createCache()
        var fetchCalled = false
        
        val result = cache.get("key1", CacheStrategy.CacheFirst) {
            fetchCalled = true
            "network-value"
        }.first()
        
        val success = result as CacheResult.Success<String>
        assertEquals("network-value", success.data)
        assertTrue(fetchCalled)
        
        // Verify both L1 and L2 warmed
        assertEquals("network-value", cache.l1Cache.get("key1")?.value)
        val l2Record = cache.l2Storage.get("key1")
        assertTrue(l2Record != null)
        assertEquals("network-value", StringTestSerializer().deserialize(l2Record.data))
    }

    /**
     * Simple string serializer for testing.
     */
    private class StringTestSerializer : KacheSerializer<String> {
        override fun serialize(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
        override fun deserialize(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
    }

    /**
     * Fake time source for testing.
     */
    private class FakeTimeSource(private var currentTime: Long = 0) : L1MemoryCache.TimeSource {
        override fun currentTimeMillis(): Long = currentTime
        fun advance(delta: Long) { currentTime += delta }
    }
}
