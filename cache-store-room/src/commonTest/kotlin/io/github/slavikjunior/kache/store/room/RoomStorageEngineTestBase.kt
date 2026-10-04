package io.github.slavikjunior.kache.store.room

import io.github.slavikjunior.kache.core.EvictionStrategy
import io.github.slavikjunior.kache.core.KacheSerializer
import io.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract every [RoomStorageEngine] must satisfy, whatever platform created it.
 *
 * Concrete test classes in `jvmTest`, `iosTest` and `androidHostTest` only supply a
 * factory, so a behavioural difference between platforms shows up as a failure here
 * rather than as a platform-specific surprise in production.
 */
abstract class RoomStorageEngineTestBase {

    /** Creates a fresh engine per test so cases cannot interfere with each other. */
    protected abstract fun createEngine(): RoomStorageEngine

    /**
     * Local so this contract does not pull `:cache-storage` in: the point is the
     * [StorageEngine] behaviour, and a backend is free to hold any serializer's bytes.
     */
    private object TextSerializer : KacheSerializer<String> {
        override fun serialize(value: String): ByteArray = value.encodeToByteArray()
        override fun deserialize(bytes: ByteArray): String = bytes.decodeToString()
    }

    @Test
    fun roundTripKeepsPayloadAndTtl() = runTest {
        val engine = createEngine()
        val record = StorageRecord.create("payload", TextSerializer, createdAt = 123L, ttl = 456.milliseconds)

        engine.put("key", record)
        val read = engine.get("key")

        assertNotNull(read)
        assertEquals(123L, read.createdAt)
        assertEquals(456.milliseconds, read.ttl)
        assertEquals("payload", TextSerializer.deserialize(read.data))
    }

    @Test
    fun getOnMissingKeyReturnsNull() = runTest {
        assertNull(createEngine().get("absent"))
    }

    @Test
    fun overwritingReplacesTheRecord() = runTest {
        val engine = createEngine()
        engine.put("k", StorageRecord.create("first", TextSerializer, createdAt = 0L))
        engine.put("k", StorageRecord.create("second", TextSerializer, createdAt = 0L))

        val read = engine.get("k")

        assertEquals(1L, engine.size(), "a primary key must be updated, not appended")
        assertEquals("second", TextSerializer.deserialize(assertNotNull(read).data))
    }

    @Test
    fun removeReportsWhetherTheKeyExisted() = runTest {
        val engine = createEngine()
        engine.put("k", StorageRecord.create("v", TextSerializer, createdAt = 0L))

        assertTrue(engine.remove("k"))
        assertFalse(engine.remove("k"))
        assertEquals(0L, engine.size())
    }

    @Test
    fun clearRemovesEveryRecord() = runTest {
        val engine = createEngine()
        engine.put("a", StorageRecord.create("1", TextSerializer, createdAt = 0L))
        engine.put("b", StorageRecord.create("2", TextSerializer, createdAt = 0L))

        engine.clear()

        assertEquals(0L, engine.size())
    }

    @Test
    fun sizeCountsRecords() = runTest {
        val engine = createEngine()
        engine.put("a", StorageRecord.create("1", TextSerializer, createdAt = 0L))
        engine.put("b", StorageRecord.create("2", TextSerializer, createdAt = 0L))
        engine.put("c", StorageRecord.create("3", TextSerializer, createdAt = 0L))

        assertEquals(3L, engine.size())
    }

    @Test
    fun removeExpiredDropsOnlyExpiredRecords() = runTest {
        val engine = createEngine()
        engine.put("fresh", StorageRecord.create("1", TextSerializer, createdAt = 0L, ttl = 10.seconds))
        engine.put("stale", StorageRecord.create("2", TextSerializer, createdAt = 0L, ttl = 10.milliseconds))

        val removed = engine.removeExpired(now = 1_000L)

        assertEquals(1L, removed)
        assertNotNull(engine.get("fresh"))
        assertNull(engine.get("stale"))
    }

    @Test
    fun removeExpiredKeepsRecordsWithoutTtl() = runTest {
        val engine = createEngine()
        engine.put("forever", StorageRecord.create("v", TextSerializer, createdAt = 0L, ttl = null))

        assertEquals(0L, engine.removeExpired(now = Long.MAX_VALUE))
        assertEquals(1L, engine.size())
    }

    @Test
    fun removeExpiredOnAnEmptyStoreReportsZero() = runTest {
        assertEquals(0L, createEngine().removeExpired(now = 1_000L))
    }

    @Test
    fun anExpiredRecordIsStillReadableUntilReaped() = runTest {
        // StaleWhileRevalidate depends on this: the engine must hand back the value and
        // leave the decision about staleness to the strategy above.
        val engine = createEngine()
        engine.put("k", StorageRecord.create("v", TextSerializer, createdAt = 0L, ttl = 10.milliseconds))

        val read = engine.get("k")

        assertNotNull(read)
        assertTrue(read.isExpired(currentTimeMillis = 1_000L))
        assertEquals(1L, engine.size(), "reading must not reap")
    }

    @Test
    fun binaryPayloadSurvivesIntact() = runTest {
        val engine = createEngine()
        // Arbitrary bytes, not text: a cache may hold any serialized form.
        val payload = byteArrayOf(0, -1, 127, -128, 42, 10, 13)

        engine.put("bin", StorageRecord(byteArrayOf(), payload, createdAt = 0L))
        val read = engine.get("bin")

        assertContentEqualsBytes(payload, assertNotNull(read).data)
    }

    @Test
    fun touchPersistsTheAccessTimestamp() = runTest {
        val engine = createEngine()
        engine.put("k", StorageRecord.create("v", TextSerializer, createdAt = 100L))

        assertTrue(engine.touch("k", accessedAt = 500L), "touch must report that it persisted the timestamp")

        assertEquals(500L, assertNotNull(engine.get("k")).lastAccessedAt)
    }

    @Test
    fun touchOnAMissingKeyReportsFalse() = runTest {
        assertFalse(createEngine().touch("absent", accessedAt = 500L))
    }

    @Test
    fun touchLeavesThePayloadAndTtlAlone() = runTest {
        val engine = createEngine()
        engine.put("k", StorageRecord.create("v", TextSerializer, createdAt = 100L, ttl = 10.seconds))

        engine.touch("k", accessedAt = 500L)
        val read = assertNotNull(engine.get("k"))

        assertEquals("v", read.data.decodeToString())
        assertEquals(100L, read.createdAt)
        assertEquals(10.seconds, read.ttl)
    }

    @Test
    fun evictionCandidatesRankByTheOldestAccessFirst() = runTest {
        val engine = createEngine()
        engine.put("touched-late", StorageRecord.create("a", TextSerializer, createdAt = 100L).copy(lastAccessedAt = 100L))
        engine.put("touched-early", StorageRecord.create("b", TextSerializer, createdAt = 200L).copy(lastAccessedAt = 50L))
        engine.put("never-touched", StorageRecord.create("c", TextSerializer, createdAt = 300L).copy(lastAccessedAt = 300L))

        val candidates = engine.evictionCandidates(EvictionStrategy.LRU, limit = 10, now = 0L)

        assertEquals(listOf("touched-early", "touched-late", "never-touched"), candidates)
    }

    @Test
    fun evictionCandidatesRankByTheOldestWriteFirstUnderFifo() = runTest {
        val engine = createEngine()
        engine.put("written-first", StorageRecord.create("a", TextSerializer, createdAt = 100L).copy(lastAccessedAt = 900L))
        engine.put("written-last", StorageRecord.create("b", TextSerializer, createdAt = 300L).copy(lastAccessedAt = 100L))

        val candidates = engine.evictionCandidates(EvictionStrategy.FIFO, limit = 10, now = 0L)

        assertEquals(listOf("written-first", "written-last"), candidates)
    }

    @Test
    fun evictionCandidatesPutExpiredRecordsFirst() = runTest {
        val engine = createEngine()
        engine.put("fresh-but-youngest", StorageRecord.create("a", TextSerializer, createdAt = 100L))
        engine.put("stale-but-oldest", StorageRecord.create("b", TextSerializer, createdAt = 50L, ttl = 10.milliseconds))

        // Every strategy would rank "fresh-but-youngest" first; the expired record must still win.
        val candidates = engine.evictionCandidates(EvictionStrategy.LIFO, limit = 1, now = 1_000L)

        assertEquals(listOf("stale-but-oldest"), candidates)
    }

    @Test
    fun evictionCandidatesHonourTheLimit() = runTest {
        val engine = createEngine()
        repeat(5) { i -> engine.put("k$i", StorageRecord.create("v", TextSerializer, createdAt = i.toLong())) }

        assertEquals(2, engine.evictionCandidates(EvictionStrategy.FIFO, limit = 2, now = 0L).size)
    }

    @Test
    fun evictionCandidatesOfAnEmptyStoreAreEmpty() = runTest {
        assertTrue(createEngine().evictionCandidates(EvictionStrategy.LRU, limit = 10, now = 0L).isEmpty())
    }

    @Test
    fun theAccessTimestampSurvivesAWrite() = runTest {
        val engine = createEngine()
        engine.put("k", StorageRecord.create("v", TextSerializer, createdAt = 100L).copy(lastAccessedAt = 400L))

        val read = assertNotNull(engine.get("k"))

        assertEquals(400L, read.lastAccessedAt, "the stored access timestamp must be persisted, not recomputed")
    }

    private fun assertContentEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}