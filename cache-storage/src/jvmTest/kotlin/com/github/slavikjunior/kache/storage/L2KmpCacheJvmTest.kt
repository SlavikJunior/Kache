package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.CacheOrigin
import com.github.slavikjunior.kache.core.CacheResult
import com.github.slavikjunior.kache.core.CacheStrategy
import com.github.slavikjunior.kache.core.KacheException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class L2KmpCacheJvmTest {

    private val rootDir = java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "kache-test-${java.util.UUID.randomUUID()}")
    private val storageEngine = FileStorageEngine.create(rootDir.absolutePath)
    private val serializer = StringSerializer()
    private val cache = L2KmpCache<String, String>(storageEngine, serializer)

    @Test
    fun `put and get value successfully`() = runBlocking {
        cache.put("key1", "value1")
        
        val result = cache.get("key1", CacheStrategy.CacheFirst, null).first()
        assertTrue(result is CacheResult.Success)
        assertEquals("value1", (result as CacheResult.Success<String>).data)
        assertEquals(CacheOrigin.DISK, (result as CacheResult.Success<String>).origin)
    }

    @Test
    fun `get non-existent key returns ExpiredException`() = runBlocking {
        val result = cache.get("nonexistent", CacheStrategy.CacheFirst, null).first()
        assertTrue(result is CacheResult.Error)
        assertTrue(result.error is KacheException.ExpiredException)
    }

    @Test
    fun `invalidate removes existing key`() = runBlocking {
        cache.put("key1", "value1")
        
        cache.invalidate("key1")

        val result = cache.get("key1", CacheStrategy.CacheFirst, null).first()
        assertTrue(result is CacheResult.Error)
    }

    @Test
    fun `clear removes all entries`() = runBlocking {
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        cache.clear()

        assertTrue(cache.get("key1", CacheStrategy.CacheFirst, null).first() is CacheResult.Error)
        assertTrue(cache.get("key2", CacheStrategy.CacheFirst, null).first() is CacheResult.Error)
    }

    @Test
    fun `getOrLoad loads value when cache miss`() = runBlocking {
        var loadCount = 0
        val flow = cache.get("key1", CacheStrategy.CacheFirst) {
            loadCount++
            "loaded-value"
        }
        val result = flow.first()
        
        assertTrue(result is CacheResult.Success)
        assertEquals("loaded-value", (result as CacheResult.Success<String>).data)
        assertEquals(CacheOrigin.NETWORK, (result as CacheResult.Success<String>).origin)
        assertEquals(1, loadCount)

        // Second call should use cache
        val flow2 = cache.get("key1", CacheStrategy.CacheFirst) {
            loadCount++
            "should-not-be-called"
        }
        val result2 = flow2.first()
        assertTrue(result2 is CacheResult.Success)
        assertEquals("loaded-value", (result2 as CacheResult.Success<String>).data)
        assertEquals(CacheOrigin.DISK, (result2 as CacheResult.Success<String>).origin)
        assertEquals(1, loadCount) // loader not called again
    }

    @Test
    fun `TTL expiration works`() = runBlocking {
        // Put a value with 100ms TTL
        cache.put("key1", "value1", 100L)

        // Should be available immediately
        val result1 = cache.get("key1", CacheStrategy.CacheFirst, null).first()
        assertTrue(result1 is CacheResult.Success)

        // Sleep past TTL
        Thread.sleep(200L)

        // Should now be expired
        val result2 = cache.get("key1", CacheStrategy.CacheFirst, null).first()
        assertTrue(result2 is CacheResult.Error)
        assertTrue(result2.error is KacheException.ExpiredException)
    }

    @Test
    fun `no TTL means never expires`() = runBlocking {
        // Put a value with no TTL
        cache.put("key1", "value1", null)

        // Should be available
        val result1 = cache.get("key1", CacheStrategy.CacheFirst, null).first()
        assertTrue(result1 is CacheResult.Success)

        // Sleep significantly
        Thread.sleep(200L)

        // Should still be available
        val result2 = cache.get("key1", CacheStrategy.CacheFirst, null).first()
        assertTrue(result2 is CacheResult.Success)
    }

    @Test
    fun `NetworkFirst strategy tries network first`() = runBlocking {
        var networkCalled = false
        val flow = cache.get("key1", CacheStrategy.NetworkFirst) {
            networkCalled = true
            "network-value"
        }
        val result = flow.first()
        
        assertTrue(result is CacheResult.Success)
        assertEquals("network-value", (result as CacheResult.Success<String>).data)
        assertEquals(CacheOrigin.NETWORK, (result as CacheResult.Success<String>).origin)
        assertTrue(networkCalled)
    }

    @Test
    fun `CacheAndNetwork emits cache then network`() = runBlocking {
        // Pre-populate cache
        cache.put("key1", "cached-value")
        
        var networkCalled = false
        val flow = cache.get("key1", CacheStrategy.CacheAndNetwork) {
            networkCalled = true
            "network-value"
        }
        
        // Collect all emissions from a single subscription
        val results = flow.toList()
        
        // First emission should be from cache
        assertEquals(2, results.size)
        val result1 = results[0]
        assertTrue(result1 is CacheResult.Success)
        assertEquals("cached-value", (result1 as CacheResult.Success<String>).data)
        assertEquals(CacheOrigin.DISK, (result1 as CacheResult.Success<String>).origin)
        
        // Second emission should be from network
        val result2 = results[1]
        assertTrue(result2 is CacheResult.Success)
        assertEquals("network-value", (result2 as CacheResult.Success<String>).data)
        assertEquals(CacheOrigin.NETWORK, (result2 as CacheResult.Success<String>).origin)
        assertTrue(networkCalled)
    }
}