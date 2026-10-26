package io.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Covers the events [KacheLogger] receives.
 *
 * The point of the logger is that a cache failure becomes observable without a debugger, so
 * these tests assert on the event stream rather than only on the returned values — a
 * strategy can return the right result while reporting nothing, and that would still be a
 * defect.
 */
class KacheLoggerTest {

    private val time = MutableTimeSource(initialTimeMillis = 0L)
    private val serializer = StringSerializer()
    private val engine = InMemoryStorageEngine(serializer)
    private val logger = RecordingLogger()

    private fun cache(
        retryPolicy: RetryPolicy = RetryPolicy.None,
        maxSize: Long? = null,
    ): L2KmpCache<String, String> = L2KmpCache(
        storageEngine = engine,
        valueSerializer = serializer,
        defaultTtl = TTL,
        timeSource = time,
        retryPolicy = retryPolicy,
        maxSize = maxSize,
        logger = logger,
    )

    @Test
    fun aColdReadReportsTheMissThenTheFetch() = runTest {
        cache().get("k", CacheStrategy.CacheFirst) { "fresh" }.toList()

        assertEquals(
            listOf("miss:k:CacheFirst", "fetch:k:1"),
            logger.events,
            "a miss must be visible before the fetch it causes",
        )
    }

    @Test
    fun aHitReportsWhereTheValueCameFrom() = runTest {
        val cache = cache()
        cache.put("k", "cached")

        cache.get("k", CacheStrategy.CacheFirst) { "unused" }.toList()

        assertEquals(listOf("hit:k:DISK"), logger.events, "a hit must not be mistaken for a fetch")
    }

    @Test
    fun anExpiredRecordIsReportedAsAMiss() = runTest {
        val cache = cache()
        cache.put("k", "cached")
        time.advance(TTL + 1.milliseconds)

        cache.get("k", CacheStrategy.CacheFirst) { "fresh" }.toList()

        assertEquals(
            listOf("miss:k:CacheFirst", "fetch:k:1"),
            logger.events,
            "an expired record may not be served as fresh, so it is a miss",
        )
    }

    @Test
    fun aFailedAttemptAndItsRetryAreReported() = runTest {
        var calls = 0

        cache(retryPolicy = RetryPolicy.fixed(attempts = 1, delay = 10.milliseconds))
            .get("k", CacheStrategy.CacheFirst) {
                calls++
                if (calls == 1) throw TestNetworkException("offline") else "fresh"
            }
            .toList()

        assertEquals(
            listOf("miss:k:CacheFirst", "fetch:k:1", "retry:k:1:10ms", "fetch:k:2"),
            logger.events,
            "the retry must name the attempt that failed, not the one about to run",
        )
    }

    @Test
    fun anExhaustedFetchIsReportedAsAnError() = runTest {
        val results = cache().get("k", CacheStrategy.CacheFirst) {
            throw TestNetworkException("offline")
        }.toList()

        assertTrue(
            results.last() is CacheResult.Error,
            "the flow must still report the failure to the caller",
        )
        assertEquals(listOf("miss:k:CacheFirst", "fetch:k:1", "error:k:NetworkException"), logger.events)
        assertTrue(
            logger.errors.single() is KacheException.NetworkException,
            "the logger must receive the typed exception, not a message",
        )
    }

    @Test
    fun readingWithNoFetcherReportsAMissAndACacheMissError() = runTest {
        cache().get("k", CacheStrategy.CacheFirst, fetcher = null).toList()

        assertEquals(
            listOf("miss:k:CacheFirst", "error:k:CacheMissException"),
            logger.events,
        )
    }

    @Test
    fun cacheAndNetworkReportsTheHitAndThenTheFetch() = runTest {
        val cache = cache()
        cache.put("k", "cached")

        cache.get("k", CacheStrategy.CacheAndNetwork) { "fresh" }.toList()

        assertEquals(
            listOf("hit:k:DISK", "fetch:k:1"),
            logger.events,
            "this strategy always fetches, even when the cache answered",
        )
    }

    @Test
    fun anEvictionNamesTheRecordThatWasDropped() = runTest {
        val cache = cache(maxSize = 1)

        cache.put("a", "first")
        cache.put("b", "second")

        val evicted = logger.events.filter { it.startsWith("evict:") }
        assertEquals(1, evicted.size, "one record over the limit, one eviction expected")
        assertEquals(
            listOf("evict:a"),
            evicted,
            "the record just written is protected from the eviction it triggered",
        )
    }

    @Test
    fun aCacheWithoutALoggerStaysSilentAndWorks() = runTest {
        val silent = L2KmpCache<String, String>(
            storageEngine = engine,
            valueSerializer = serializer,
            defaultTtl = TTL,
            timeSource = time,
        )

        val result = silent.get("k", CacheStrategy.CacheFirst) { "fresh" }.toList().last()

        assertEquals(CacheResult.Success("fresh", CacheOrigin.NETWORK), result)
        assertEquals(emptyList(), logger.events, "the default logger must not report anything")
    }

    /** Records every event in order, so a test can assert on the sequence a caller would see. */
    private class RecordingLogger : KacheLogger {

        val events = mutableListOf<String>()
        val errors = mutableListOf<KacheException>()

        override fun onMiss(key: String, strategy: CacheStrategy) {
            events += "miss:$key:${strategy.name}"
        }

        override fun onHit(key: String, origin: CacheOrigin) {
            events += "hit:$key:${origin.name}"
        }

        override fun onFetch(key: String, attempt: Int) {
            events += "fetch:$key:$attempt"
        }

        override fun onRetry(key: String, failedAttempt: Int, delay: Duration) {
            events += "retry:$key:$failedAttempt:${delay.inWholeMilliseconds}ms"
        }

        override fun onError(key: String, error: KacheException) {
            events += "error:$key:${error::class.simpleName}"
            errors += error
        }

        override fun onEvict(key: String) {
            events += "evict:$key"
        }
    }

    private companion object {
        val TTL: Duration = 1.milliseconds * 1000
    }
}
