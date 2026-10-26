package io.github.slavikjunior.kache.sample.core.cachelog

import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.KacheException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Covers what the panel and the snackbars are fed from.
 *
 * These assertions are on the state the UI renders, not on "a method was called": the log is
 * the only thing between the library and the screen, so a counter that does not move is a
 * defect the user sees.
 */
class CacheEventLogTest {

    private val log = CacheEventLog(capacity = 3)

    @Test
    fun countsEveryKindOfEventSeparately() {
        log.recordMiss("cars:makes")
        log.recordHit("cars:models:tesla", CacheOrigin.DISK)
        log.onFetch("cars:makes", attempt = 1)
        log.onRetry("cars:makes", failedAttempt = 1, delay = 400.milliseconds)
        log.onError("cars:makes", KacheException.NetworkException(IllegalStateException("offline")))
        log.onEvict("cars:specs:2015:tesla:model3")

        val counters = log.state.value.counters
        assertEquals(1, counters.hits)
        assertEquals(1, counters.misses)
        assertEquals(1, counters.fetches)
        assertEquals(1, counters.retries)
        assertEquals(1, counters.errors)
        assertEquals(1, counters.evictions)
    }

    @Test
    fun hitRatioIsDerivedFromHitsAndMissesOnly() {
        assertEquals(0f, log.state.value.counters.hitRatio, "zero lookups must not divide by zero")

        log.recordHit("a", CacheOrigin.MEMORY)
        log.recordHit("b", CacheOrigin.DISK)
        log.recordMiss("c")

        assertEquals(3, log.state.value.counters.lookups)
        assertEquals(2f / 3f, log.state.value.counters.hitRatio)
    }

    @Test
    fun keepsNewestEventsFirstAndDropsTheOldestBeyondCapacity() {
        log.recordMiss("first")
        log.recordMiss("second")
        log.recordMiss("third")
        log.recordMiss("fourth")

        val messages = log.state.value.events.map { it.message }
        assertEquals(3, messages.size, "capacity is three")
        assertTrue(messages.first().contains("fourth"), "newest first, was $messages")
        assertTrue(messages.none { it.contains("first") }, "oldest must be dropped, was $messages")
    }

    @Test
    fun assignsMonotonicIdsSoTheUiCanTellNewEventsFromRedrawnOnes() {
        log.recordMiss("a")
        log.recordMiss("b")
        log.recordHit("c", CacheOrigin.NETWORK)

        val ids = log.state.value.events.map { it.id }
        assertEquals(ids.sortedDescending(), ids, "newest first means descending ids")
        assertEquals(ids.distinct().size, ids.size, "ids must be unique")
    }

    @Test
    fun onlyResultEventsAreWorthInterruptingTheUser() {
        assertTrue(CacheEventKind.HIT.worthSnackbar)
        assertTrue(CacheEventKind.MISS.worthSnackbar)
        assertTrue(CacheEventKind.RETRY.worthSnackbar)
        assertTrue(CacheEventKind.ERROR.worthSnackbar)
        assertTrue(CacheEventKind.EVICTION.worthSnackbar)
        assertTrue(
            !CacheEventKind.FETCH.worthSnackbar,
            "a fetch has no result yet; the panel is where it belongs",
        )
    }

    @Test
    fun clearForgetsEventsCountersAndIds() {
        log.recordMiss("a")
        log.recordHit("b", CacheOrigin.DISK)

        log.clear()

        val state = log.state.value
        assertEquals(emptyList(), state.events)
        assertEquals(CacheCounters(), state.counters)
        assertEquals(0L, state.nextId, "ids restart, so a cleared log cannot replay old events")
    }

    @Test
    fun theMessageNamesTheKeyAndWhatHappenedToIt() {
        log.recordHit("cars:makes", CacheOrigin.DISK)
        log.recordMiss("cars:models:tesla")

        val messages = log.state.value.events.map { it.message }
        assertEquals("miss · cars:models:tesla · CacheFirst", messages[0])
        assertEquals("hit · cars:makes · DISK", messages[1])
    }

    private fun CacheEventLog.recordMiss(key: String) {
        onMiss(key, CacheStrategy.CacheFirst)
    }

    private fun CacheEventLog.recordHit(key: String, origin: CacheOrigin) {
        onHit(key, origin)
    }
}
