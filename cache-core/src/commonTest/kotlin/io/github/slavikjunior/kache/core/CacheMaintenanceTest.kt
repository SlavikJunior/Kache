package io.github.slavikjunior.kache.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Capacity limiting, access tracking and eviction ordering, exercised through the public
 * cache API rather than through [StorageMaintenance] directly.
 */
class CacheMaintenanceTest {

    private val time = MutableTimeSource(initialTimeMillis = 0L)

    // --- capacity -------------------------------------------------------------

    @Test
    fun aWriteBeyondTheLimitEvictsTheOldestRecord() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(
            storageEngine = engine,
            valueSerializer = StringSerializer(),
            timeSource = time,
            maxSize = 2,
        )

        cache.put("a", "1", null)
        time.advance(1.seconds)
        cache.put("b", "2", null)
        time.advance(1.seconds)
        cache.put("c", "3", null)

        assertEquals(2L, engine.size())
        assertNull(engine.get("a"), "the oldest record must be the one evicted")
        assertNotNull(engine.get("b"))
        assertNotNull(engine.get("c"))
    }

    @Test
    fun theRecordThatTriggeredEvictionSurvives() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(engine, StringSerializer(), timeSource = time, maxSize = 1)

        cache.put("old", "1", null)
        time.advance(1.seconds)
        cache.put("new", "2", null)

        assertNotNull(engine.get("new"), "the newest write must never evict itself")
        assertNull(engine.get("old"))
    }

    @Test
    fun noLimitMeansTheCacheGrowsUnbounded() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(engine, StringSerializer(), timeSource = time, maxSize = null)

        repeat(50) { i ->
            time.advance(1.seconds)
            cache.put("k$i", "v$i", null)
        }

        assertEquals(50L, engine.size())
    }

    @Test
    fun aZeroOrNegativeLimitMeansNoLimit() = runTest {
        for (limit in listOf(0L, -1L)) {
            val engine = InMemoryStorageEngine()
            val cache = L2KmpCache<String, String>(engine, StringSerializer(), timeSource = time, maxSize = limit)

            repeat(10) { i ->
                time.advance(1.seconds)
                cache.put("k$i", "v$i", null)
            }

            assertEquals(10L, engine.size(), "a limit of $limit must not delete anything")
        }
    }

    @Test
    fun fillingExactlyToTheLimitEvictsNothing() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(engine, StringSerializer(), timeSource = time, maxSize = 3)

        repeat(3) { i ->
            time.advance(1.seconds)
            cache.put("k$i", "v$i", null)
        }

        assertEquals(3L, engine.size())
        repeat(3) { i -> assertNotNull(engine.get("k$i"), "a record at the limit must not be evicted") }
    }

    @Test
    fun expiredRecordsAreEvictedBeforeFreshOnes() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(engine, StringSerializer(), timeSource = time, maxSize = 2)

        cache.put("dead", "1", 100.milliseconds)
        time.advance(200.milliseconds)
        cache.put("live-old", "2", 10.seconds)
        time.advance(1.seconds)
        cache.put("live-new", "3", 10.seconds)

        assertEquals(2L, engine.size())
        assertNull(engine.get("dead"), "the expired record must go first even though it was written first")
        assertNotNull(engine.get("live-old"))
        assertNotNull(engine.get("live-new"))
    }

    // --- strategies -----------------------------------------------------------

    @Test
    fun lruKeepsTheRecordThatWasReadMostRecently() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(
            engine,
            StringSerializer(),
            timeSource = time,
            maxSize = 2,
            evictionStrategy = EvictionStrategy.LRU,
            touchGranularity = Duration.ZERO,
        )

        cache.put("a", "1", null)
        time.advance(1.seconds)
        cache.put("b", "2", null)
        time.advance(1.seconds)
        // Read "a" so it becomes the most recently used, leaving "b" as the coldest.
        cache.get("a", CacheStrategy.CacheFirst, fetcher = null).first()
        time.advance(1.seconds)
        cache.put("c", "3", null)

        assertNotNull(engine.get("a"), "the recently read record must survive")
        assertNull(engine.get("b"), "the record not read since it was written must be evicted")
    }

    @Test
    fun fifoIgnoresReadsAndEvictsByWriteOrder() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(
            engine,
            StringSerializer(),
            timeSource = time,
            maxSize = 2,
            evictionStrategy = EvictionStrategy.FIFO,
            touchGranularity = Duration.ZERO,
        )

        cache.put("a", "1", null)
        time.advance(1.seconds)
        cache.put("b", "2", null)
        cache.get("a", CacheStrategy.CacheFirst, fetcher = null).first()
        time.advance(1.seconds)
        cache.put("c", "3", null)

        // LRU would have kept "a" here. FIFO must not care that it was read.
        assertNull(engine.get("a"), "FIFO must evict by write order regardless of reads")
        assertNotNull(engine.get("b"))
        assertNotNull(engine.get("c"))
    }

    @Test
    fun lifoEvictsTheNewestRecord() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(
            engine,
            StringSerializer(),
            timeSource = time,
            maxSize = 2,
            evictionStrategy = EvictionStrategy.LIFO,
        )

        cache.put("a", "1", null)
        time.advance(1.seconds)
        cache.put("b", "2", null)
        time.advance(1.seconds)
        cache.put("c", "3", null)

        // "c" is protected as the just-written record, so the next newest, "b", goes.
        assertNull(engine.get("b"), "LIFO must evict the newest record it is allowed to")
        assertNotNull(engine.get("a"))
        assertNotNull(engine.get("c"))
    }

    @Test
    fun mruEvictsTheRecordThatWasJustRead() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(
            engine,
            StringSerializer(),
            timeSource = time,
            maxSize = 2,
            evictionStrategy = EvictionStrategy.MRU,
            touchGranularity = Duration.ZERO,
        )

        cache.put("a", "1", null)
        time.advance(1.seconds)
        cache.put("b", "2", null)
        cache.get("a", CacheStrategy.CacheFirst, fetcher = null).first()
        time.advance(1.seconds)
        cache.put("c", "3", null)

        assertNull(engine.get("a"), "MRU must evict the record read most recently")
        assertNotNull(engine.get("b"))
        assertNotNull(engine.get("c"))
    }

    // --- access tracking ------------------------------------------------------

    @Test
    fun aReadInsideTheTouchWindowDoesNotWrite() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(
            engine,
            StringSerializer(),
            timeSource = time,
            maxSize = 10,
            touchGranularity = 1.minutes,
        )

        cache.put("k", "v", null)
        val writesAfterPut = engine.writeCount

        // Still inside the window: the access timestamp must not be refreshed.
        time.advance(30.seconds)
        repeat(5) { cache.get("k", CacheStrategy.CacheFirst, fetcher = null).first() }

        assertEquals(writesAfterPut, engine.writeCount, "a read inside the window must not reach storage")
    }

    @Test
    fun aReadPastTheTouchWindowWritesOnce() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(
            engine,
            StringSerializer(),
            timeSource = time,
            maxSize = 10,
            touchGranularity = 1.minutes,
        )

        cache.put("k", "v", null)
        val writesAfterPut = engine.writeCount

        time.advance(2.minutes)
        repeat(5) { cache.get("k", CacheStrategy.CacheFirst, fetcher = null).first() }

        // Five reads past the window, one write: the first read refreshed the timestamp and
        // the rest fell inside the new window.
        assertEquals(writesAfterPut + 1, engine.writeCount)
    }

    @Test
    fun aZeroWindowRefreshesTheTimestampOnEveryRead() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(
            engine,
            StringSerializer(),
            timeSource = time,
            maxSize = 10,
            touchGranularity = Duration.ZERO,
        )

        cache.put("k", "v", null)
        assertEquals(1, engine.writeCount, "the initial write")
        time.advance(1.seconds)
        cache.get("k", CacheStrategy.CacheFirst, fetcher = null).first()

        assertEquals(2, engine.writeCount, "a zero window must refresh the timestamp on every read")
        assertEquals(1_000L, assertNotNull(engine.get("k")).lastAccessedAt, "the refresh must record the read time")
    }

    @Test
    fun accessIsNotTrackedWithoutACapacityLimit() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(
            storageEngine = engine,
            valueSerializer = StringSerializer(),
            timeSource = time,
            maxSize = null,
            touchGranularity = Duration.ZERO,
        )

        cache.put("k", "v", null)
        val writesAfterPut = engine.writeCount

        time.advance(1.seconds)
        repeat(5) { cache.get("k", CacheStrategy.CacheFirst, fetcher = null).first() }

        // Without a limit there will never be an eviction, so refreshing access timestamps
        // would be a storage write bought for nothing.
        assertEquals(writesAfterPut, engine.writeCount)
    }

    // --- reaping --------------------------------------------------------------

    @Test
    fun reapingIsOffUnlessAnIntervalIsGiven() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(
            storageEngine = engine,
            valueSerializer = StringSerializer(),
            timeSource = time,
            autoReapEvery = null,
        )

        cache.put("k", "v", 100.milliseconds)
        time.advance(1.minutes)
        // Long past expiry and well past any plausible interval: nothing may remove it.
        assertNotNull(engine.get("k"), "an expired record must stay until reaping is configured")
        assertEquals(1L, engine.size())
        cache.stopAutoReap()
    }

    @Test
    fun reapingDeletesExpiredRecordsAndKeepsFreshOnes() = runTest {
        val engine = InMemoryStorageEngine()
        val maintenance = StorageMaintenance(
            storageEngine = engine,
            timeSource = time,
            autoReapEvery = 50.milliseconds,
        )

        engine.put("dead", StorageRecord.create("1", StringSerializer(), createdAt = 0L, ttl = 100.milliseconds))
        engine.put("live", StorageRecord.create("2", StringSerializer(), createdAt = 0L, ttl = 1.hours))

        time.advance(1.seconds)
        val removed = maintenance.reapExpired()

        assertEquals(1L, removed)
        assertNull(engine.get("dead"))
        assertNotNull(engine.get("live"))
    }

    @Test
    fun theReaperSweepsOnItsOwnInterval() = runBlocking {
        // Real clock and real time, both on purpose. The reaper runs on its own dispatcher
        // reading the clock it was given, and `runTest` fast-forwards `delay` without letting
        // another dispatcher run — which would make this test pass or fail on scheduler timing
        // rather than on the reaper actually working.
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(
            storageEngine = engine,
            valueSerializer = StringSerializer(),
            autoReapEvery = 50.milliseconds,
        )

        cache.put("dead", "v", 100.milliseconds)
        assertNotNull(engine.get("dead"), "the first sweep waits a full interval")

        delay(600)
        cache.stopAutoReap()

        assertNull(engine.get("dead"), "the reaper must have deleted the expired record on its own")
    }

    @Test
    fun stoppingTheReaperLeavesTheCacheUsable() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = L2KmpCache<String, String>(
            storageEngine = engine,
            valueSerializer = StringSerializer(),
            timeSource = time,
            autoReapEvery = 30.milliseconds,
        )

        cache.stopAutoReap()
        cache.put("k", "v", null)

        assertEquals("v", assertIs<CacheResult.Success<String>>(
            cache.get("k", CacheStrategy.CacheFirst, fetcher = null).first()
        ).data)
    }

    // --- chain cache ----------------------------------------------------------

    @Test
    fun theChainCacheAlsoEnforcesItsLimit() = runTest {
        val engine = InMemoryStorageEngine()
        val cache = ChainKmpCache<String, String>(
            l1Cache = L1MemoryCache(maxSize = 10, timeSource = time),
            l2Storage = engine,
            serializer = StringSerializer(),
            maxSize = 2,
        )

        cache.put("a", "1", null)
        time.advance(1.seconds)
        cache.put("b", "2", null)
        time.advance(1.seconds)
        cache.put("c", "3", null)

        assertEquals(2L, engine.size())
        assertNull(engine.get("a"))
    }

    @Test
    fun theChainCacheReapsL1AsWellAsL2() = runTest {
        val engine = InMemoryStorageEngine()
        val l1 = L1MemoryCache<String, String>(maxSize = 10, timeSource = time)
        val maintenance = StorageMaintenance(
            storageEngine = engine,
            timeSource = time,
            autoReapEvery = 50.milliseconds,
        )

        l1.put("in-memory", "v", 100.milliseconds)
        engine.put("on-disk", StorageRecord.create("v", StringSerializer(), createdAt = 0L, ttl = 100.milliseconds))
        time.advance(1.seconds)

        maintenance.reapExpired(memoryTier = { l1.removeExpired() })

        assertNull(l1.get("in-memory"), "the in-memory tier must be swept too")
        assertNull(engine.get("on-disk"))
    }
}
