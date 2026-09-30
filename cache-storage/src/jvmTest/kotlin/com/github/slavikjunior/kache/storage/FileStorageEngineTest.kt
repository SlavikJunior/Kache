package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.CacheResult
import com.github.slavikjunior.kache.core.KacheException
import com.github.slavikjunior.kache.core.L2KmpCache
import com.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStorageEngineTest {

    private val rootDir: File = File(
        System.getProperty("java.io.tmpdir"),
        "kache-test-${UUID.randomUUID()}",
    ).also { it.mkdirs() }

    private val engine = FileStorageEngine(rootDir.absolutePath)
    private val serializer = StringSerializer()

    @AfterTest
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    private fun record(value: String, createdAt: Long = 0L, ttlMillis: Long? = null) =
        StorageRecord.create(value, serializer, createdAt = createdAt, ttlMillis = ttlMillis)

    @Test
    fun `a written record can be read back`() = runTest {
        engine.put("key1", record("value1"))

        val stored = assertIsNotNullRecord(engine.get("key1"))

        assertEquals("value1", serializer.deserialize(stored.data))
        assertNull(stored.ttlMillis)
    }

    @Test
    fun `an unknown key reads as null`() = runTest {
        assertNull(engine.get("missing"))
    }

    @Test
    fun `a record keeps its TTL across a write`() = runTest {
        engine.put("key1", record("value1", createdAt = 1_000L, ttlMillis = 500L))

        val stored = assertIsNotNullRecord(engine.get("key1"))

        assertEquals(500L, stored.ttlMillis)
        assertEquals(1_500L, stored.expiresAt())
    }

    @Test
    fun `writing the same key twice replaces the record`() = runTest {
        engine.put("key1", record("first"))
        engine.put("key1", record("second"))

        assertEquals("second", serializer.deserialize(assertIsNotNullRecord(engine.get("key1")).data))
        assertEquals(1L, engine.size())
    }

    @Test
    fun `a corrupt record is dropped instead of failing the read`() = runTest {
        File(rootDir, "corrupt").writeText("not a record")

        assertNull(engine.get("corrupt"))
        assertFalse(File(rootDir, "corrupt").exists())
    }

    @Test
    fun `remove reports whether a record was there`() = runTest {
        engine.put("key1", record("value1"))

        assertTrue(engine.remove("key1"))
        assertFalse(engine.remove("key1"))
        assertNull(engine.get("key1"))
    }

    @Test
    fun `clear removes every record`() = runTest {
        engine.put("key1", record("value1"))
        engine.put("key2", record("value2"))

        engine.clear()

        assertEquals(0L, engine.size())
    }

    @Test
    fun `size counts records`() = runTest {
        assertEquals(0L, engine.size())

        engine.put("key1", record("value1"))
        engine.put("key2", record("value2"))
        assertEquals(2L, engine.size())

        engine.remove("key1")
        assertEquals(1L, engine.size())
    }

    @Test
    fun `keys that are not safe file names are mapped onto safe ones`() = runTest {
        engine.put("user id/42?x=1", record("value1"))

        val stored = assertIsNotNullRecord(engine.get("user id/42?x=1"))

        assertEquals("value1", serializer.deserialize(stored.data))
    }

    @Test
    fun `an expired record is still readable, reaping is left to the caller`() = runTest {
        engine.put("key1", record("value1", createdAt = 0L, ttlMillis = 100L))

        val stored = assertIsNotNullRecord(engine.get("key1"))

        assertTrue(stored.isExpired(200L))
    }

    @Test
    fun `a write failure is reported as a typed disk write error`() = runTest {
        val readOnlyRoot = File(rootDir, "read-only").also { it.mkdirs() }
        val engineOnReadOnlyDir = FileStorageEngine(readOnlyRoot.absolutePath)
        readOnlyRoot.setReadOnly()

        try {
            assertFailsWith<KacheException.DiskWriteException> {
                engineOnReadOnlyDir.put("key1", record("value1"))
            }
        } finally {
            readOnlyRoot.setWritable(true)
        }
    }

    @Test
    fun `a directory that cannot be created is reported as a typed error`() {
        val path = File(rootDir, "file-in-the-way/child").absolutePath
        File(rootDir, "file-in-the-way").writeText("not a directory")

        assertFailsWith<KacheException.DiskWriteException> { FileStorageEngine(path) }
    }

    @Test
    fun `a cache can be layered on top of the engine`() = runTest {
        val cache = L2KmpCache<String, String>(engine, serializer)

        cache.put("key1", "value1")

        val result = assertIs<CacheResult.Success<String>>(
            cache.get("key1").first()
        )
        assertEquals("value1", result.data)
    }

    private fun assertIsNotNullRecord(record: StorageRecord<*>?): StorageRecord<*> =
        requireNotNull(record) { "expected a stored record" }
}