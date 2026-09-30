package com.github.slavikjunior.kache.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class L1MemoryCacheCommonTest {

    @Test
    fun `basic put and get`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 10)
        cache.put("key1", "value1")
        val result = cache.get("key1")
        assertEquals("value1", result?.value)
    }

    @Test
    fun `get returns null for missing key`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 10)
        val result = cache.get("missing")
        assertNull(result)
    }

    @Test
    fun `remove deletes entry`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 10)
        cache.put("key1", "value1")
        cache.remove("key1")
        val result = cache.get("key1")
        assertNull(result)
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
    fun `LRU eviction when maxSize exceeded`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 2)
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3") // Should evict key1 (LRU)

        assertNull(cache.get("key1")) // Evicted
        assertEquals("value2", cache.get("key2")?.value)
        assertEquals("value3", cache.get("key3")?.value)
    }

    @Test
    fun `LRU updates access order on get`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 2)
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        
        // Access key1 to make it recently used
        cache.get("key1")
        
        // Add key3, should evict key2 (LRU)
        cache.put("key3", "value3")
        
        assertEquals("value1", cache.get("key1")?.value) // Still there
        assertNull(cache.get("key2")) // Evicted
        assertEquals("value3", cache.get("key3")?.value)
    }

    @Test
    fun `put updates existing key and moves to MRU`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 2)
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        
        // Update key1
        cache.put("key1", "updated1")
        
        // Add key3, should evict key2 (LRU)
        cache.put("key3", "value3")
        
        assertEquals("updated1", cache.get("key1")?.value) // Still there, updated
        assertNull(cache.get("key2")) // Evicted
        assertEquals("value3", cache.get("key3")?.value)
    }

    @Test
    fun `size returns correct count`() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 10)
        assertEquals(0, cache.size())
        
        cache.put("key1", "value1")
        assertEquals(1, cache.size())
        
        cache.put("key2", "value2")
        assertEquals(2, cache.size())
        
        cache.remove("key1")
        assertEquals(1, cache.size())
        
        cache.clear()
        assertEquals(0, cache.size())
    }
}
