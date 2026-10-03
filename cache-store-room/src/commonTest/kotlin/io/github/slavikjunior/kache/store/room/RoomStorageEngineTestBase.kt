package io.github.slavikjunior.kache.store.room

import io.github.slavikjunior.kache.core.KacheSerializer
import io.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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
        val record = StorageRecord.create("payload", TextSerializer, createdAt = 123L, ttlMillis = 456L)

        engine.put("key", record)
        val read = engine.get("key")

        assertNotNull(read)
        assertEquals(123L, read.createdAt)
        assertEquals(456L, read.ttlMillis)
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
        engine.put("fresh", StorageRecord.create("1", TextSerializer, createdAt = 0L, ttlMillis = 10_000L))
        engine.put("stale", StorageRecord.create("2", TextSerializer, createdAt = 0L, ttlMillis = 10L))

        val removed = engine.removeExpired(now = 1_000L)

        assertEquals(1L, removed)
        assertNotNull(engine.get("fresh"))
        assertNull(engine.get("stale"))
    }

    @Test
    fun removeExpiredKeepsRecordsWithoutTtl() = runTest {
        val engine = createEngine()
        engine.put("forever", StorageRecord.create("v", TextSerializer, createdAt = 0L, ttlMillis = null))

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
        engine.put("k", StorageRecord.create("v", TextSerializer, createdAt = 0L, ttlMillis = 10L))

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

    private fun assertContentEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}