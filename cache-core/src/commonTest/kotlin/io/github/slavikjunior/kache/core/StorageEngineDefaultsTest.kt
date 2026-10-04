package io.github.slavikjunior.kache.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Contract of the [StorageEngine] methods a backend may choose not to implement.
 *
 * Access tracking is optional on purpose: a backend that cannot record a read cheaply should
 * keep working rather than be forced into a write per read. The cost of that choice is that
 * it silently ranks by write time, which is what these tests pin down.
 */
class StorageEngineDefaultsTest {

    @Test
    fun touchReportsUnsupportedByDefault() = runTest {
        val engine = InMemoryStorageEngine()

        assertFalse(engine.touch("k", accessedAt = 5L), "a backend that does not track access must say so")
    }

    @Test
    fun evictionCandidatesAreEmptyByDefault() = runTest {
        val engine = InMemoryStorageEngine()

        assertTrue(
            engine.evictionCandidates(EvictionStrategy.LRU, limit = 10, now = 0L).isEmpty(),
            "a backend that cannot rank must return no candidates rather than guess",
        )
    }

    @Test
    fun evictionCandidatesIgnoreANonPositiveLimit() = runTest {
        val engine = InMemoryStorageEngine()

        assertTrue(engine.evictionCandidates(EvictionStrategy.LRU, limit = 0, now = 0L).isEmpty())
        assertTrue(engine.evictionCandidates(EvictionStrategy.LRU, limit = -5, now = 0L).isEmpty())
    }

    @Test
    fun aFreshRecordCountsAsJustAccessed() {
        val record = StorageRecord(value = "v", createdAt = 1_000L, ttl = 100.milliseconds)

        assertEquals(1_000L, record.lastAccessedAt, "a new record must start out as the most recently used one")
    }

    @Test
    fun anUntouchedRecordRanksLikeAFifoEntry() = runTest {
        val engine = InMemoryStorageEngine()
        val older = StorageRecord(value = "old", createdAt = 100L, ttl = null)
        val newer = StorageRecord(value = "new", createdAt = 200L, ttl = null)
        engine.seed("a", older)
        engine.seed("b", newer)

        val byLru = engine.rankBy(EvictionStrategy.LRU)
        val byFifo = engine.rankBy(EvictionStrategy.FIFO)

        assertEquals(byFifo, byLru, "without access tracking, LRU must degenerate to write order")
    }

    @Test
    fun copyingARecordUpdatesOnlyItsAccessTimestamp() {
        val record = StorageRecord(value = "v", createdAt = 10L, lastAccessedAt = 10L)

        val touched = record.copy(lastAccessedAt = 99L)

        assertEquals(99L, touched.lastAccessedAt)
        assertEquals(10L, touched.createdAt)
        assertEquals(record.value, touched.value)
        assertContentEquals(record.data, touched.data)
        assertEquals(record.ttl, touched.ttl)
    }
}

/**
 * Ranks seeded records the way [StorageEngine.evictionCandidates] should, by applying the
 * shared [recordComparator] to what the engine actually holds.
 *
 * Lets the comparator be tested without a real backend: [InMemoryStorageEngine] keeps the
 * records and exposes them for seeding, which is all the ordering logic needs.
 */
private suspend fun InMemoryStorageEngine.rankBy(strategy: EvictionStrategy): List<String> {
    val comparator = strategy.recordComparator()
    return peekRecords()
        .map { (key, record) -> key to StorageRecord(value = record.data, createdAt = record.createdAt, ttl = record.ttl) }
        .sortedWith { left, right -> comparator.compare(left.second, right.second) }
        .map { it.first }
}
