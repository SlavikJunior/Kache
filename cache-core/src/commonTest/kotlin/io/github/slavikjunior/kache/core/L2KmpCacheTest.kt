package io.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the four strategies through [L2KmpCache], which is the single-tier
 * implementation the pipeline is driven from.
 */
class L2KmpCacheTest {

    private val time = MutableTimeSource(initialTimeMillis = 0L)
    private val serializer = StringSerializer()
    private val engine = InMemoryStorageEngine(serializer)

    private fun cache(retryPolicy: RetryPolicy = RetryPolicy.None): L2KmpCache<String, String> =
        L2KmpCache(
            storageEngine = engine,
            valueSerializer = serializer,
            defaultTtl = TTL,
            timeSource = time,
            retryPolicy = retryPolicy,
        )

    // --- CacheFirst ---

    @Test
    fun cacheFirstFetchesOnAMissAndReportsNetwork() = runTest {
        val result = cache().get("k", CacheStrategy.CacheFirst) { "fresh" }.toList().last()

        assertEquals(CacheResult.Success("fresh", CacheOrigin.NETWORK), result)
        assertEquals(1L, engine.size(), "a fetched value must be written through")
    }

    @Test
    fun cacheFirstServesTheCachedValueWithoutCallingTheFetcher() = runTest {
        val cache = cache()
        cache.put("k", "cached")
        var fetcherCalls = 0

        val result = cache.get("k", CacheStrategy.CacheFirst) {
            fetcherCalls++
            "fresh"
        }.toList().last()

        assertEquals(CacheResult.Success("cached", CacheOrigin.DISK), result)
        assertEquals(0, fetcherCalls, "a satisfied cache must not hit the fetcher")
    }

    @Test
    fun cacheFirstTreatsAnExpiredRecordAsAMiss() = runTest {
        val cache = cache()
        cache.put("k", "cached")
        time.advance(TTL + 1.milliseconds)

        val result = cache.get("k", CacheStrategy.CacheFirst) { "fresh" }.toList().last()

        assertEquals(CacheResult.Success("fresh", CacheOrigin.NETWORK), result)
    }

    @Test
    fun missWithoutFetcherIsReportedAsACacheMiss() = runTest {
        val result = cache().get("k", CacheStrategy.CacheFirst, fetcher = null).toList().last()

        val error = assertIs<CacheResult.Error<String>>(result)
        assertIs<KacheException.CacheMissException>(error.error)
        assertEquals("No fetcher provided and cache miss", error.error.message)
    }

    // --- NetworkFirst ---

    @Test
    fun networkFirstPrefersTheFetchAndRewritesTheCache() = runTest {
        val cache = cache()
        cache.put("k", "cached")

        val result = cache.get("k", CacheStrategy.NetworkFirst) { "fresh" }.toList().last()

        assertEquals(CacheResult.Success("fresh", CacheOrigin.NETWORK), result)
        assertEquals(CacheResult.Success("fresh", CacheOrigin.DISK),
            cache.get("k", CacheStrategy.CacheFirst, fetcher = null).toList().last())
    }

    @Test
    fun networkFirstFallsBackToCacheAndKeepsTheRealOrigin() = runTest {
        val cache = cache()
        cache.put("k", "cached")

        val result = cache.get("k", CacheStrategy.NetworkFirst) {
            throw TestNetworkException("offline")
        }.toList().last()

        // The failure is not surfaced when cache can take its place, but the origin is
        // honest about where the value came from.
        assertEquals(CacheResult.Success("cached", CacheOrigin.DISK), result)
    }

    @Test
    fun networkFirstReportsAMissWhenTheFetchFailsAndNothingIsCached() = runTest {
        val result = cache().get("k", CacheStrategy.NetworkFirst) {
            throw TestNetworkException("offline")
        }.toList().last()

        // Deliberate: with no cached value to fall back on, the pipeline reports a miss
        // rather than a NetworkException, because for the caller the outcome is the same
        // — nothing could be produced — and a miss is the more specific of the two.
        val error = assertIs<CacheResult.Error<String>>(result)
        assertIs<KacheException.CacheMissException>(error.error)
        assertEquals("Network fetch failed and no cached data available", error.error.message)
    }

    @Test
    fun networkFirstReportsTheSameMessageForAMissAndForAFailedFetch() = runTest {
        // Recorded as-is: NetworkFirst treats "no fetcher" and "fetch failed" alike,
        // which CacheFirst does not. See networkFirstMissesExplicitlyWhenNoFetcherIsGiven.
        val withoutFetcher = cache().get("k", CacheStrategy.NetworkFirst, fetcher = null)
            .toList().last()
        val withFailingFetcher = cache().get("k2", CacheStrategy.NetworkFirst) {
            throw TestNetworkException("offline")
        }.toList().last()

        assertEquals(
            assertIs<CacheResult.Error<String>>(withoutFetcher).error.message,
            assertIs<CacheResult.Error<String>>(withFailingFetcher).error.message,
        )
    }

    @Test
    fun cacheFirstIsTheOnlyStrategyThatNamesAMissingFetcher() = runTest {
        val cacheFirst = assertIs<CacheResult.Error<String>>(
            cache().get("a", CacheStrategy.CacheFirst, fetcher = null).toList().last(),
        )
        val networkFirst = assertIs<CacheResult.Error<String>>(
            cache().get("b", CacheStrategy.NetworkFirst, fetcher = null).toList().last(),
        )

        assertEquals("No fetcher provided and cache miss", cacheFirst.error.message)
        // Known gap: a caller who forgot to pass a fetcher gets a message blaming the
        // network, which sends them debugging the wrong layer.
        assertEquals("Network fetch failed and no cached data available", networkFirst.error.message)
    }

    // --- CacheAndNetwork ---

    @Test
    fun cacheAndNetworkEmitsCachedThenFetched() = runTest {
        val cache = cache()
        cache.put("k", "cached")

        val results = cache.get("k", CacheStrategy.CacheAndNetwork) { "fresh" }.toList()

        assertEquals(
            listOf(
                CacheResult.Success("cached", CacheOrigin.DISK),
                CacheResult.Success("fresh", CacheOrigin.NETWORK),
            ),
            results,
        )
    }

    @Test
    fun cacheAndNetworkEmitsOnlyTheFetchedValueOnAMiss() = runTest {
        val results = cache().get("k", CacheStrategy.CacheAndNetwork) { "fresh" }.toList()

        assertEquals(listOf(CacheResult.Success("fresh", CacheOrigin.NETWORK)), results)
    }

    @Test
    fun cacheAndNetworkKeepsTheCachedValueWhenTheRefreshFails() = runTest {
        val cache = cache()
        cache.put("k", "cached")

        val results = cache.get("k", CacheStrategy.CacheAndNetwork) {
            throw TestNetworkException("offline")
        }.toList()

        // No Error emission: the screen already has something to show.
        assertEquals(listOf(CacheResult.Success("cached", CacheOrigin.DISK)), results)
    }

    // --- StaleWhileRevalidate ---

    @Test
    fun staleWhileRevalidateServesAnExpiredValueWithAStaleOrigin() = runTest {
        val cache = cache()
        cache.put("k", "cached")
        time.advance(TTL + 1.milliseconds)

        val results = cache.get("k", CacheStrategy.StaleWhileRevalidate) { "fresh" }.toList()

        assertEquals(
            listOf(
                CacheResult.Success("cached", CacheOrigin.DISK_STALE),
                CacheResult.Success("fresh", CacheOrigin.NETWORK),
            ),
            results,
        )
    }

    @Test
    fun staleWhileRevalidateLeavesTheStaleRecordWhenTheRefreshFails() = runTest {
        val cache = cache()
        cache.put("k", "cached")
        time.advance(TTL + 1.milliseconds)

        val results = cache.get("k", CacheStrategy.StaleWhileRevalidate) {
            throw TestNetworkException("offline")
        }.toList()

        assertEquals(listOf(CacheResult.Success("cached", CacheOrigin.DISK_STALE)), results)
        // Still there, so the next attempt can serve it again.
        assertEquals(1L, engine.size())
    }

    // --- Write, invalidate, clear ---

    @Test
    fun putThenReadRoundTripsThroughSerialization() = runTest {
        val cache = cache()
        cache.put("k", "value")

        val read = cache.get("k", CacheStrategy.CacheFirst, fetcher = null).toList().last()

        assertEquals(CacheResult.Success("value", CacheOrigin.DISK), read)
    }

    @Test
    fun putAppliesTheDefaultTtlAndHonoursAnExplicitOne() = runTest {
        val cache = cache()
        cache.put("default", "v")

        time.advance(TTL + 1.milliseconds)
        assertIs<CacheResult.Error<String>>(
            cache.get("default", CacheStrategy.CacheFirst, fetcher = null).toList().last(),
        )

        cache.put("explicit", "v", ttl = 10.seconds)
        time.advance(TTL + 1.milliseconds)
        assertIs<CacheResult.Success<String>>(
            cache.get("explicit", CacheStrategy.CacheFirst, fetcher = null).toList().last(),
        )
    }

    @Test
    fun invalidateRemovesTheStoredEntry() = runTest {
        val cache = cache()
        cache.put("k", "v")

        cache.invalidate("k")

        assertEquals(0L, engine.size())
        assertIs<CacheResult.Error<String>>(
            cache.get("k", CacheStrategy.CacheFirst, fetcher = null).toList().last(),
        )
    }

    @Test
    fun clearEmptiesStorage() = runTest {
        val cache = cache()
        cache.put("a", "1")
        cache.put("b", "2")

        cache.clear()

        assertEquals(0L, engine.size())
    }

    // --- Retry integration ---

    @Test
    fun retryPolicyDecidesWhetherTheFetcherRunsAgain() = runTest {
        var attempts = 0
        val cache = cache(RetryPolicy.fixed(attempts = 2, delay = 1.milliseconds))

        val result = cache.get("k", CacheStrategy.CacheFirst) {
            attempts++
            throw TestNetworkException("always down")
        }.toList().last()

        // Initial attempt plus two retries.
        assertEquals(3, attempts)
        assertIs<CacheResult.Error<String>>(result)
    }

    @Test
    fun retriesStopAtTheFirstSuccess() = runTest {
        var attempts = 0
        val cache = cache(RetryPolicy.fixed(attempts = 5, delay = 1.milliseconds))

        val result = cache.get("k", CacheStrategy.CacheFirst) {
            attempts++
            if (attempts < 3) throw TestNetworkException("flaky") else "recovered"
        }.toList().last()

        assertEquals(3, attempts)
        assertEquals(CacheResult.Success("recovered", CacheOrigin.NETWORK), result)
    }

    @Test
    fun defaultPolicyRunsTheFetcherExactlyOnce() = runTest {
        var attempts = 0
        val cache = cache()

        cache.get("k", CacheStrategy.CacheFirst) {
            attempts++
            throw TestNetworkException("down")
        }.toList()

        assertEquals(1, attempts, "RetryPolicy.None must not amplify traffic")
    }

    @Test
    fun removedExpiredEntriesAreReported() = runTest {
        val cache = cache()
        cache.put("k", "v")
        time.advance(TTL + 1.milliseconds)

        // removeExpired lives on StorageEngine, not on the cache contract.
        val removed = engine.removeExpired(time.currentTimeMillis())

        assertEquals(1L, removed)
        assertEquals(0L, engine.size())
    }

    @Test
    fun customKeyToStringIsUsedForStorage() = runTest {
        val cache = L2KmpCache<String, String>(
            storageEngine = engine,
            valueSerializer = serializer,
            keyToString = { "prefix-$it" },
            timeSource = time,
        )

        cache.put("k", "v")

        // The default mapping would store under "k"; the prefixed one must not collide.
        val stored = engine.get("prefix-k")
        assertTrue(stored != null, "the mapping must reach the engine")
        assertTrue(engine.get("k") == null, "the raw key must not be used")
    }

    private companion object {
        val TTL = 1.seconds
    }
}