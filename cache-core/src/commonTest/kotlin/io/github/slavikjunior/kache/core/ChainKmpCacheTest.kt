package io.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the L1+L2 composition, where the interesting behaviour is which tier answers and
 * how a value written to L2 ends up in L1.
 */
class ChainKmpCacheTest {

    private val time = MutableTimeSource(initialTimeMillis = 0L)
    private val serializer = StringSerializer()
    private val engine = InMemoryStorageEngine(serializer)
    private val l1 = L1MemoryCache<String, String>(maxSize = 4, timeSource = time)

    private fun chain(defaultTtl: Duration? = TTL): ChainKmpCache<String, String> = ChainKmpCache(
        l1Cache = l1,
        l2Storage = engine,
        serializer = serializer,
        defaultTtl = defaultTtl,
    )

    @Test
    fun secondReadIsServedFromMemoryWithoutTouchingStorage() = runTest {
        val cache = chain()
        cache.put("k", "v")
        val writesAfterPut = engine.writeCount

        val result = cache.get("k", CacheStrategy.CacheFirst) { "fresh" }.toList().last()

        assertEquals(CacheResult.Success("v", CacheOrigin.MEMORY), result)
        assertEquals(writesAfterPut, engine.writeCount, "a memory hit must not write through")
    }

    @Test
    fun memoryEntryIsEvictedAndTheNextReadFallsThroughToDisk() = runTest {
        val smallL1 = L1MemoryCache<String, String>(maxSize = 1, timeSource = time)
        val cache = ChainKmpCache(
            l1Cache = smallL1,
            l2Storage = engine,
            serializer = serializer,
            defaultTtl = TTL,
        )
        cache.put("a", "1")
        cache.put("b", "2") // evicts "a" from L1, but "a" is still in L2

        val result = cache.get("a", CacheStrategy.CacheFirst) { "fresh" }.toList().last()

        assertEquals(CacheResult.Success("1", CacheOrigin.DISK), result)
    }

    @Test
    fun diskHitIsPromotedIntoMemory() = runTest {
        val cache = chain()
        // Write straight to L2 so the first read has to come from storage.
        engine.seed(
            "k",
            StorageRecord.create("from-disk", serializer, createdAt = 0L, ttl = TTL),
        )

        cache.get("k", CacheStrategy.CacheFirst, fetcher = null).toList()

        val second = cache.get("k", CacheStrategy.CacheFirst, fetcher = null).toList().last()
        assertEquals(CacheResult.Success("from-disk", CacheOrigin.MEMORY), second)
    }

    @Test
    fun bothTiersShareOneClockSoTheyCannotDisagreeOnExpiry() = runTest {
        val cache = chain()
        cache.put("k", "v")
        time.advance(TTL + 1.milliseconds)

        // L1 must not serve a value L2 considers expired, or a stale value would be
        // reported as fresh depending on which tier answered.
        val result = cache.get("k", CacheStrategy.CacheFirst) { "fresh" }.toList().last()

        assertEquals(CacheResult.Success("fresh", CacheOrigin.NETWORK), result)
    }

    @Test
    fun staleWhileRevalidateReportsMemoryStaleAfterExpiry() = runTest {
        val cache = chain()
        cache.put("k", "v")
        time.advance(TTL + 1.milliseconds)

        val results = cache.get("k", CacheStrategy.StaleWhileRevalidate) { "fresh" }.toList()

        assertTrue(
            results.any { it == CacheResult.Success("v", CacheOrigin.MEMORY_STALE) },
            "expected a MEMORY_STALE emission, got $results",
        )
    }

    @Test
    fun clearEmptiesBothTiers() = runTest {
        val cache = chain()
        cache.put("k", "v")
        assertEquals(1, l1.size())
        assertEquals(1L, engine.size())

        cache.clear()

        assertEquals(0, l1.size(), "L1 must be cleared too")
        assertEquals(0L, engine.size())
    }

    @Test
    fun invalidateRemovesFromBothTiers() = runTest {
        val cache = chain()
        cache.put("k", "v")

        cache.invalidate("k")

        // A read-only miss is an Error emission, not a null result.
        val after = cache.get("k", CacheStrategy.CacheFirst, fetcher = null).toList().last()
        assertIs<CacheResult.Error<String>>(after)
        assertIs<KacheException.CacheMissException>(after.error)
        assertEquals(0L, engine.size())
        assertEquals(0, l1.size(), "L1 must forget the entry as well")
    }

    @Test
    fun aFetchedValueIsWrittenThroughToStorageAndMemory() = runTest {
        val cache = chain()

        val result = cache.get("k", CacheStrategy.CacheFirst) { "fresh" }.toList().last()

        assertEquals(CacheResult.Success("fresh", CacheOrigin.NETWORK), result)
        assertEquals(1L, engine.size())
        assertEquals(1, l1.size())
        // Reading again must not fetch.
        assertEquals(
            CacheResult.Success("fresh", CacheOrigin.MEMORY),
            cache.get("k", CacheStrategy.CacheFirst, fetcher = null).toList().last(),
        )
    }

    @Test
    fun missOnBothTiersIsReportedAsACacheMiss() = runTest {
        val result = chain().get("absent", CacheStrategy.CacheFirst, fetcher = null).toList().last()

        val error = assertIs<CacheResult.Error<String>>(result)
        assertIs<KacheException.CacheMissException>(error.error)
    }

    private companion object {
        val TTL = 1.seconds
    }
}