package com.github.slavikjunior.kache.store.room

import com.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Common tests for [RoomStorageEngine].
 *
 * Platform-specific test suites (jvmTest) must provide a concrete
 * [RoomStorageEngine] instance via [createTestEngine].
 */
abstract class RoomStorageEngineTestBase {

    /**
     * Platform-specific factory method. Each target must implement this.
     */
    protected abstract suspend fun createTestEngine(): RoomStorageEngine

    private fun record(data: ByteArray, createdAt: Long, ttlMillis: Long? = null): StorageRecord<ByteArray> {
        return StorageRecord(value = data, data = data, createdAt = createdAt, ttlMillis = ttlMillis)
    }

    @Test
    fun get_nonExistentKey_returnsNull() = runTest {
        val engine = createTestEngine()
        val result = engine.get("missing-key")
        assertNull(result)
    }

    @Test
    fun put_andGet_returnsStoredRecord() = runTest {
        val engine = createTestEngine()
        val record = record("hello".toByteArray(), 1000L, 5000L)

        engine.put("key1", record)
        val retrieved = engine.get("key1")

        assertNotNull(retrieved)
        assertEquals("hello", retrieved.data.decodeToString())
        assertEquals(1000L, retrieved.createdAt)
        assertEquals(5000L, retrieved.ttlMillis)
    }

    @Test
    fun put_overwritesExistingKey() = runTest {
        val engine = createTestEngine()
        val record1 = record("v1".toByteArray(), 1000L, null)
        val record2 = record("v2".toByteArray(), 2000L, 3000L)

        engine.put("key", record1)
        engine.put("key", record2)

        val retrieved = engine.get("key")
        assertNotNull(retrieved)
        assertEquals("v2", retrieved.data.decodeToString())
        assertEquals(2000L, retrieved.createdAt)
        assertEquals(3000L, retrieved.ttlMillis)
    }

    @Test
    fun remove_existingKey_returnsTrue() = runTest {
        val engine = createTestEngine()
        val record = record("data".toByteArray(), 1000L, null)
        engine.put("key", record)

        val removed = engine.remove("key")
        assertTrue(removed)
        assertNull(engine.get("key"))
    }

    @Test
    fun remove_nonExistentKey_returnsFalse() = runTest {
        val engine = createTestEngine()
        val removed = engine.remove("missing")
        assertFalse(removed)
    }

    @Test
    fun clear_removesAllRecords() = runTest {
        val engine = createTestEngine()
        engine.put("k1", record("v1".toByteArray(), 1000L, null))
        engine.put("k2", record("v2".toByteArray(), 2000L, null))

        engine.clear()

        assertEquals(0L, engine.size())
        assertNull(engine.get("k1"))
        assertNull(engine.get("k2"))
    }

    @Test
    fun size_returnsCorrectCount() = runTest {
        val engine = createTestEngine()
        assertEquals(0L, engine.size())

        engine.put("k1", record("v1".toByteArray(), 1000L, null))
        assertEquals(1L, engine.size())

        engine.put("k2", record("v2".toByteArray(), 2000L, null))
        assertEquals(2L, engine.size())

        engine.remove("k1")
        assertEquals(1L, engine.size())

        engine.clear()
        assertEquals(0L, engine.size())
    }

    @Test
    fun removeExpired_deletesOnlyExpiredRecords() = runTest {
        val engine = createTestEngine()

        // Expired: created=1000, ttl=2000 → expires at 3000
        engine.put("expired1", record("e1".toByteArray(), createdAt = 1000L, ttlMillis = 2000L))

        // Not expired: created=5000, ttl=3000 → expires at 8000
        engine.put("live1", record("l1".toByteArray(), createdAt = 5000L, ttlMillis = 3000L))

        // Never expires: ttlMillis=null
        engine.put("eternal", record("et".toByteArray(), createdAt = 1000L, ttlMillis = null))

        // Expired: created=2000, ttl=1000 → expires at 3000
        engine.put("expired2", record("e2".toByteArray(), createdAt = 2000L, ttlMillis = 1000L))

        val now = 6000L
        val removed = engine.removeExpired(now)

        assertEquals(2L, removed) // expired1 and expired2
        assertEquals(2L, engine.size()) // live1 and eternal remain

        assertNull(engine.get("expired1"))
        assertNull(engine.get("expired2"))
        assertNotNull(engine.get("live1"))
        assertNotNull(engine.get("eternal"))
    }

    @Test
    fun removeExpired_withNoExpiredRecords_returnsZero() = runTest {
        val engine = createTestEngine()
        engine.put("k1", record("v1".toByteArray(), createdAt = 5000L, ttlMillis = 10000L))
        engine.put("k2", record("v2".toByteArray(), createdAt = 6000L, ttlMillis = null))

        val removed = engine.removeExpired(now = 7000L)
        assertEquals(0L, removed)
        assertEquals(2L, engine.size())
    }

    @Test
    fun removeExpired_withEmptyStorage_returnsZero() = runTest {
        val engine = createTestEngine()
        val removed = engine.removeExpired(now = 10000L)
        assertEquals(0L, removed)
    }

    @Test
    fun put_nullTtl_neverExpires() = runTest {
        val engine = createTestEngine()
        val record = record("eternal".toByteArray(), createdAt = 1000L, ttlMillis = null)
        engine.put("key", record)

        // Far future
        val removed = engine.removeExpired(now = Long.MAX_VALUE)
        assertEquals(0L, removed)

        val retrieved = engine.get("key")
        assertNotNull(retrieved)
        assertNull(retrieved.ttlMillis)
    }
}