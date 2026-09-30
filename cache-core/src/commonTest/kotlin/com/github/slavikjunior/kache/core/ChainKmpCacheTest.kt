package com.github.slavikjunior.kache.core

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChainKmpCacheTest {

    private val timeSource = MutableTimeSource(0)
    private val l1 = L1MemoryCache<String, String>(maxSize = 10, timeSource = timeSource)
    private val l2 = TestStorageEngine()
    private val serializer = StringTestSerializer()

    private fun cache(
        defaultTtlMs: Long? = null,
        retryPolicy: RetryPolicy = RetryPolicy.None,
    ) = ChainKmpCache(l1, l2, serializer, defaultTtlMs = defaultTtlMs, retryPolicy = retryPolicy)

    // --- CacheFirst ---

    @Test
    fun `CacheFirst serves an L1 hit from memory`() = runTest {
        val cache = cache()
        cache.put("key1", "l1-value")

        val result = cache.get("key1").first()

        assertEquals("l1-value", assertIs<CacheResult.Success<String>>(result).data)
        assertEquals(CacheOrigin.MEMORY, assertIs<CacheResult.Success<String>>(result).origin)
    }

    @Test
    fun `CacheFirst falls through to L2 on an L1 miss and promotes the value`() = runTest {
        val cache = cache()
        l2.put("key1", StorageRecord.create("l2-value", serializer, createdAt = 0L))

        val first = assertIs<CacheResult.Success<String>>(cache.get("key1").first())
        assertEquals("l2-value", first.data)
        assertEquals(CacheOrigin.DISK, first.origin)

        val second = assertIs<CacheResult.Success<String>>(cache.get("key1").first())
        assertEquals(CacheOrigin.MEMORY, second.origin)
    }

    @Test
    fun `CacheFirst fetches on a miss and fills both tiers`() = runTest {
        val cache = cache()

        val result = assertIs<CacheResult.Success<String>>(
            cache.get("key1") { "network-value" }.first()
        )

        assertEquals("network-value", result.data)
        assertEquals(CacheOrigin.NETWORK, result.origin)
        assertEquals("network-value", l1.get("key1")?.value)
        assertEquals("network-value", serializer.deserialize(l2.get("key1")!!.data))
    }

    @Test
    fun `CacheFirst reports a miss when there is no fetcher`() = runTest {
        val result = cache().get("key1").first()

        assertIs<KacheException.CacheMissException>(assertIs<CacheResult.Error<String>>(result).error)
    }

    @Test
    fun `CacheFirst treats an expired record as a miss`() = runTest {
        val cache = cache()
        cache.put("key1", "value1", ttlMs = 100)
        timeSource.advance(150)

        val result = assertIs<CacheResult.Error<String>>(cache.get("key1").first())

        assertIs<KacheException.CacheMissException>(result.error)
    }

    @Test
    fun `a fetcher failure is reported as a network error`() = runTest {
        val result = cache().get("key1") { throw IllegalStateException("network down") }.first()

        val error = assertIs<KacheException.NetworkException>(assertIs<CacheResult.Error<String>>(result).error)
        assertEquals("network down", error.cause.message)
    }

    // --- NetworkFirst ---

    @Test
    fun `NetworkFirst prefers the network and writes through`() = runTest {
        val cache = cache()

        val result = assertIs<CacheResult.Success<String>>(
            cache.get("key1", CacheStrategy.NetworkFirst) { "network-value" }.first()
        )

        assertEquals("network-value", result.data)
        assertEquals(CacheOrigin.NETWORK, result.origin)
        assertEquals("network-value", l1.get("key1")?.value)
    }

    @Test
    fun `NetworkFirst falls back to L1 when the network fails`() = runTest {
        val cache = cache()
        cache.put("key1", "l1-value")

        val result = assertIs<CacheResult.Success<String>>(
            cache.get("key1", CacheStrategy.NetworkFirst) { throw IllegalStateException("down") }.first()
        )

        assertEquals("l1-value", result.data)
        assertEquals(CacheOrigin.MEMORY, result.origin)
    }

    @Test
    fun `NetworkFirst falls back to L2 when the network fails and L1 is cold`() = runTest {
        val cache = cache()
        l2.put("key1", StorageRecord.create("l2-value", serializer, createdAt = 0L))

        val result = assertIs<CacheResult.Success<String>>(
            cache.get("key1", CacheStrategy.NetworkFirst) { throw IllegalStateException("down") }.first()
        )

        assertEquals("l2-value", result.data)
        assertEquals(CacheOrigin.DISK, result.origin)
    }

    @Test
    fun `NetworkFirst reports a miss when the network fails and nothing is cached`() = runTest {
        val result = cache().get("key1", CacheStrategy.NetworkFirst) {
            throw IllegalStateException("down")
        }.first()

        assertIs<KacheException.CacheMissException>(assertIs<CacheResult.Error<String>>(result).error)
    }

    // --- CacheAndNetwork ---

    @Test
    fun `CacheAndNetwork emits the cached value and then the fetched one`() = runTest {
        val cache = cache()
        cache.put("key1", "cached-value")

        val results = cache.get("key1", CacheStrategy.CacheAndNetwork) { "network-value" }.toList()

        assertEquals(2, results.size)
        assertEquals("cached-value", assertIs<CacheResult.Success<String>>(results[0]).data)
        assertEquals(CacheOrigin.MEMORY, assertIs<CacheResult.Success<String>>(results[0]).origin)
        assertEquals("network-value", assertIs<CacheResult.Success<String>>(results[1]).data)
        assertEquals(CacheOrigin.NETWORK, assertIs<CacheResult.Success<String>>(results[1]).origin)
    }

    @Test
    fun `CacheAndNetwork emits only the fetched value on a cold cache`() = runTest {
        val results = cache().get("key1", CacheStrategy.CacheAndNetwork) { "network-value" }.toList()

        assertEquals(1, results.size)
        assertEquals(CacheOrigin.NETWORK, assertIs<CacheResult.Success<String>>(results.single()).origin)
    }

    @Test
    fun `CacheAndNetwork hides a network failure when cached data exists`() = runTest {
        val cache = cache()
        cache.put("key1", "cached-value")

        val results = cache.get("key1", CacheStrategy.CacheAndNetwork) {
            throw IllegalStateException("down")
        }.toList()

        assertEquals(1, results.size)
        assertEquals("cached-value", assertIs<CacheResult.Success<String>>(results.single()).data)
    }

    @Test
    fun `CacheAndNetwork surfaces a network failure on a cold cache`() = runTest {
        val result = cache().get("key1", CacheStrategy.CacheAndNetwork) {
            throw IllegalStateException("down")
        }.first()

        assertIs<KacheException.NetworkException>(assertIs<CacheResult.Error<String>>(result).error)
    }

    // --- StaleWhileRevalidate ---

    @Test
    fun `StaleWhileRevalidate serves an expired L1 entry and refreshes it`() = runTest {
        val cache = cache()
        cache.put("key1", "stale-value", ttlMs = 100)
        timeSource.advance(150)

        val results = cache.get("key1", CacheStrategy.StaleWhileRevalidate) { "fresh-value" }.toList()

        assertEquals(2, results.size)
        assertEquals("stale-value", assertIs<CacheResult.Success<String>>(results[0]).data)
        assertEquals(CacheOrigin.MEMORY_STALE, assertIs<CacheResult.Success<String>>(results[0]).origin)
        assertEquals("fresh-value", assertIs<CacheResult.Success<String>>(results[1]).data)
        assertEquals(CacheOrigin.NETWORK, assertIs<CacheResult.Success<String>>(results[1]).origin)
    }

    @Test
    fun `StaleWhileRevalidate serves an expired L2 entry and refreshes it`() = runTest {
        val cache = cache()
        l2.put("key1", StorageRecord.create("stale-value", serializer, createdAt = 0L, ttlMillis = 100))
        timeSource.advance(150)

        val results = cache.get("key1", CacheStrategy.StaleWhileRevalidate) { "fresh-value" }.toList()

        assertEquals(2, results.size)
        assertEquals(CacheOrigin.DISK_STALE, assertIs<CacheResult.Success<String>>(results[0]).origin)
        assertEquals("fresh-value", assertIs<CacheResult.Success<String>>(results[1]).data)
    }

    @Test
    fun `StaleWhileRevalidate keeps the stale entry when the refresh fails`() = runTest {
        val cache = cache()
        cache.put("key1", "stale-value", ttlMs = 100)
        timeSource.advance(150)

        val results = cache.get("key1", CacheStrategy.StaleWhileRevalidate) {
            throw IllegalStateException("down")
        }.toList()

        assertEquals(1, results.size)
        assertEquals("stale-value", assertIs<CacheResult.Success<String>>(results.single()).data)
        assertEquals("stale-value", l1.getStale("key1")?.value)
    }

    @Test
    fun `StaleWhileRevalidate promotes a stale L2 record without extending it`() = runTest {
        val cache = cache()
        l2.put("key1", StorageRecord.create("stale-value", serializer, createdAt = 0L, ttlMillis = 100))
        timeSource.advance(150)

        cache.get("key1", CacheStrategy.StaleWhileRevalidate) { throw IllegalStateException("down") }.toList()

        // Promotion must not hand the record a fresh full-length lifetime: it is still
        // readable as stale data, and it is expired one millisecond from now.
        val promoted = assertNotNull(l1.getStale("key1"))
        timeSource.advance(1)

        assertTrue(promoted.isExpired(timeSource.currentTimeMillis()))
    }

    // --- writes ---

    @Test
    fun `put stores the value in both tiers`() = runTest {
        val cache = cache()

        cache.put("key1", "new-value")

        assertEquals("new-value", l1.get("key1")?.value)
        assertEquals("new-value", serializer.deserialize(l2.get("key1")!!.data))
    }

    @Test
    fun `put falls back to the default TTL`() = runTest {
        val cache = cache(defaultTtlMs = 100)

        cache.put("key1", "value1")
        timeSource.advance(150)

        assertNull(l1.get("key1"))
    }

    @Test
    fun `invalidate removes the entry from both tiers`() = runTest {
        val cache = cache()
        cache.put("key1", "value1")

        cache.invalidate("key1")

        assertNull(l1.get("key1"))
        assertNull(l2.get("key1"))
    }

    @Test
    fun `clear empties both tiers`() = runTest {
        val cache = cache()
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        cache.clear()

        assertEquals(0, l1.size())
        assertEquals(0L, l2.size())
    }

    // --- retries ---

    @Test
    fun `no retries means the fetcher runs once`() = runTest {
        var attempts = 0

        cache(retryPolicy = RetryPolicy.None).get("key1") {
            attempts++
            throw IllegalStateException("down")
        }.first()

        assertEquals(1, attempts)
    }

    @Test
    fun `a failing fetcher is retried until it succeeds`() = runTest {
        var attempts = 0
        val retryPolicy = RetryPolicy.Exponential(maxAttempts = 3, initialDelayMs = 100, jitterRatio = 0.0)

        val result = cache(retryPolicy = retryPolicy).get("key1") {
            attempts++
            if (attempts < 3) throw IllegalStateException("down") else "network-value"
        }.first()

        assertEquals(3, attempts)
        assertEquals("network-value", assertIs<CacheResult.Success<String>>(result).data)
    }

    @Test
    fun `retries stop at the configured attempt limit`() = runTest {
        var attempts = 0
        val retryPolicy = RetryPolicy.Exponential(maxAttempts = 2, initialDelayMs = 10, jitterRatio = 0.0)

        val result = cache(retryPolicy = retryPolicy).get("key1") {
            attempts++
            throw IllegalStateException("down")
        }.first()

        assertEquals(retryPolicy.totalAttempts, attempts)
        assertIs<KacheException.NetworkException>(assertIs<CacheResult.Error<String>>(result).error)
    }

    @Test
    fun `a fixed interval policy does not delay between attempts`() = runTest {
        var attempts = 0
        val retryPolicy = RetryPolicy.fixed(attempts = 2, delayMs = 50)

        cache(retryPolicy = retryPolicy).get("key1") {
            attempts++
            throw IllegalStateException("down")
        }.first()

        assertEquals(3, attempts)
        assertEquals(50, retryPolicy.delayAfter(1))
    }

    @Test
    fun `cancelling a collection is not reported as a network failure`() = runTest {
        val result = runCatching {
            withTimeout(100) {
                cache().get("key1") {
                    delay(10_000)
                    "never"
                }.first()
            }
        }

        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
    }
}