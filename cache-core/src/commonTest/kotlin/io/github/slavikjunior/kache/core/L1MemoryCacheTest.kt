package io.github.slavikjunior.kache.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class L1MemoryCacheTest {

    private val time = MutableTimeSource(initialTimeMillis = 0L)

    @Test
    fun putThenGetReturnsTheValue() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 4, timeSource = time)

        cache.put("k", "v")

        assertEquals("v", cache.get("k")?.value)
        assertEquals(1, cache.size())
    }

    @Test
    fun getOnMissingKeyReturnsNull() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 4, timeSource = time)

        assertNull(cache.get("absent"))
    }

    @Test
    fun capacityMustBePositive() {
        assertFailsWith<IllegalArgumentException> { L1MemoryCache<String, String>(maxSize = 0) }
        assertFailsWith<IllegalArgumentException> { L1MemoryCache<String, String>(maxSize = -1) }
    }

    @Test
    fun evictionDropsTheLeastRecentlyUsedEntry() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 2, timeSource = time)
        cache.put("a", "1")
        cache.put("b", "2")

        // Touch "a" so "b" becomes the eviction candidate.
        cache.get("a")
        cache.put("c", "3")

        assertNotNull(cache.get("a"), "recently used entry must survive")
        assertNull(cache.get("b"), "least recently used entry must be evicted")
        assertEquals("3", cache.get("c")?.value)
        assertEquals(2, cache.size())
    }

    @Test
    fun updateRefreshesPositionWithoutGrowingTheCache() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 2, timeSource = time)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("a", "1-updated")

        assertEquals("1-updated", cache.get("a")?.value)
        assertEquals(2, cache.size())
    }

    @Test
    fun expiredEntryIsNotReturnedAndIsReapedOnRead() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 4, timeSource = time)
        cache.put("k", "v", ttlMs = 100L)

        time.advance(101L)

        assertNull(cache.get("k"))
        assertEquals(0, cache.size(), "reading an expired entry must drop it")
    }

    @Test
    fun defaultTtlAppliesWhenPutOmitsIt() = runTest {
        val cache = L1MemoryCache<String, String>(
            maxSize = 4,
            defaultTtlMs = 100L,
            timeSource = time,
        )
        cache.put("k", "v")

        time.advance(99L)
        assertNotNull(cache.get("k"), "still inside the default TTL")

        time.advance(2L)
        assertNull(cache.get("k"), "past the default TTL")
    }

    @Test
    fun explicitTtlOverridesTheDefault() = runTest {
        val cache = L1MemoryCache<String, String>(
            maxSize = 4,
            defaultTtlMs = 100L,
            timeSource = time,
        )
        cache.put("k", "v", ttlMs = 10_000L)

        time.advance(500L)

        assertNotNull(cache.get("k"))
    }

    @Test
    fun entryWithoutTtlSurvivesTimeTravel() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 4, timeSource = time)
        cache.put("k", "v", ttlMs = null)

        time.advance(1_000_000L)

        assertNotNull(cache.get("k"))
    }

    @Test
    fun getStaleReturnsAnExpiredValueWithoutEvictingIt() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 4, timeSource = time)
        cache.put("k", "v", ttlMs = 100L)

        time.advance(500L)

        // This is what StaleWhileRevalidate needs: a usable value during a refresh.
        val stale = cache.getStale("k")
        assertNotNull(stale)
        assertEquals("v", stale.value)
        assertTrue(stale.isExpired(time.currentTimeMillis()))
        assertEquals(1, cache.size(), "getStale must not reap")
    }

    @Test
    fun getStaleOnMissingKeyReturnsNull() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 4, timeSource = time)

        assertNull(cache.getStale("absent"))
    }

    @Test
    fun removeExpiredDropsOnlyExpiredEntries() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 8, timeSource = time)
        cache.put("fresh", "1", ttlMs = 10_000L)
        cache.put("stale", "2", ttlMs = 100L)
        cache.put("forever", "3", ttlMs = null)

        time.advance(500L)

        assertEquals(1, cache.removeExpired())
        assertNull(cache.get("stale"))
        assertNotNull(cache.get("fresh"))
        assertNotNull(cache.get("forever"))
    }

    @Test
    fun sizeIncludesExpiredEntriesUntilTheyAreReaped() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 8, timeSource = time)
        cache.put("k", "v", ttlMs = 100L)

        time.advance(500L)

        assertEquals(1, cache.size(), "size counts records, it does not filter by TTL")
    }

    @Test
    fun removeAndClearDropEntries() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 8, timeSource = time)
        cache.put("a", "1")
        cache.put("b", "2")

        cache.remove("a")
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))

        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun removeOfMissingKeyIsANoOp() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 8, timeSource = time)
        cache.put("a", "1")

        cache.remove("absent")

        assertEquals(1, cache.size())
    }
}