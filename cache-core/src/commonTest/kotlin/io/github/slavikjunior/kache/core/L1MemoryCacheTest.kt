package io.github.slavikjunior.kache.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    fun anExpiredEntryIsEvictedBeforeTheLeastRecentlyUsedFreshOne() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 3, timeSource = time)
        cache.put("fresh-lru", "1")
        cache.put("also-fresh", "2")
        cache.put("doomed", "3", ttl = 100.milliseconds)
        // "doomed" is now the most recently used, so plain LRU would drop "fresh-lru".
        cache.get("doomed")

        time.advance(200.milliseconds)
        cache.put("newcomer", "4")

        assertNull(cache.getStale("doomed"), "the expired entry must be the one evicted")
        assertNotNull(cache.get("fresh-lru"), "a fresh LRU head must survive while an expired entry exists")
        assertEquals("2", cache.get("also-fresh")?.value)
        assertEquals(3, cache.size())
    }

    @Test
    fun evictionKeepsTheFreshEntryWhileExpiredOnesAreDropped() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 3, timeSource = time)
        cache.put("keep", "1")
        cache.put("dead-1", "2", ttl = 100.milliseconds)
        cache.put("dead-2", "3", ttl = 100.milliseconds)

        time.advance(200.milliseconds)
        // Two writes, two evictions: both must come out of the dead pair, not out of "keep".
        cache.put("new-1", "4")
        cache.put("new-2", "5")

        assertEquals("1", cache.get("keep")?.value, "a fresh entry must not be evicted while dead ones remain")
        assertEquals("4", cache.get("new-1")?.value)
        assertEquals("5", cache.get("new-2")?.value)
        assertEquals(3, cache.size())
    }

    @Test
    fun capacityIsRespectedWhenEveryEntryIsExpired() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 2, timeSource = time)
        cache.put("a", "1", ttl = 100.milliseconds)
        cache.put("b", "2", ttl = 100.milliseconds)

        time.advance(200.milliseconds)
        repeat(5) { i -> cache.put("k$i", "v$i", ttl = 100.milliseconds) }

        assertEquals(2, cache.size(), "expired entries must not accumulate past capacity")
    }

    @Test
    fun anExpiredEntryReadThroughGetStaleIsStillEvictedFirst() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 2, timeSource = time)
        cache.put("fresh", "1")
        cache.put("doomed", "2", ttl = 100.milliseconds)
        // getStale keeps the entry and promotes it, so it is no longer the LRU head.
        assertEquals("2", cache.getStale("doomed")?.value)

        time.advance(200.milliseconds)
        cache.put("newcomer", "3")

        assertNotNull(cache.get("fresh"), "a stale read must not shield a fresh entry from eviction")
        assertNull(cache.getStale("doomed"))
        assertEquals("3", cache.get("newcomer")?.value)
    }

    @Test
    fun evictionKeepsTheEntryJustWritten() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 1, timeSource = time)
        cache.put("old", "1")

        cache.put("fresh-write", "2")

        assertEquals("2", cache.get("fresh-write")?.value, "the write that triggered eviction must survive it")
        assertNull(cache.get("old"))
        assertEquals(1, cache.size())
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
        cache.put("k", "v", ttl = 100.milliseconds)

        time.advance(101.milliseconds)

        assertNull(cache.get("k"))
        assertEquals(0, cache.size(), "reading an expired entry must drop it")
    }

    @Test
    fun defaultTtlAppliesWhenPutOmitsIt() = runTest {
        val cache = L1MemoryCache<String, String>(
            maxSize = 4,
            defaultTtl = 100.milliseconds,
            timeSource = time,
        )
        cache.put("k", "v")

        time.advance(99.milliseconds)
        assertNotNull(cache.get("k"), "still inside the default TTL")

        time.advance(2.milliseconds)
        assertNull(cache.get("k"), "past the default TTL")
    }

    @Test
    fun explicitTtlOverridesTheDefault() = runTest {
        val cache = L1MemoryCache<String, String>(
            maxSize = 4,
            defaultTtl = 100.milliseconds,
            timeSource = time,
        )
        cache.put("k", "v", ttl = 10.seconds)

        time.advance(500.milliseconds)

        assertNotNull(cache.get("k"))
    }

    @Test
    fun entryWithoutTtlSurvivesTimeTravel() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 4, timeSource = time)
        cache.put("k", "v", ttl = null)

        time.advance(1_000.seconds)

        assertNotNull(cache.get("k"))
    }

    @Test
    fun getStaleReturnsAnExpiredValueWithoutEvictingIt() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 4, timeSource = time)
        cache.put("k", "v", ttl = 100.milliseconds)

        time.advance(500.milliseconds)

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
        cache.put("fresh", "1", ttl = 10.seconds)
        cache.put("stale", "2", ttl = 100.milliseconds)
        cache.put("forever", "3", ttl = null)

        time.advance(500.milliseconds)

        assertEquals(1, cache.removeExpired())
        assertNull(cache.get("stale"))
        assertNotNull(cache.get("fresh"))
        assertNotNull(cache.get("forever"))
    }

    @Test
    fun sizeIncludesExpiredEntriesUntilTheyAreReaped() = runTest {
        val cache = L1MemoryCache<String, String>(maxSize = 8, timeSource = time)
        cache.put("k", "v", ttl = 100.milliseconds)

        time.advance(500.milliseconds)

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