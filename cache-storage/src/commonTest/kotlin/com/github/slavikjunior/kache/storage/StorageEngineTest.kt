package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.CacheOrigin
import com.github.slavikjunior.kache.core.CacheResult
import com.github.slavikjunior.kache.core.CacheStrategy
import com.github.slavikjunior.kache.core.KacheException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStorageEngineTest {

    private val rootDir = java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "kache-test-${java.util.UUID.randomUUID()}")
    private val engine = FileStorageEngine.create(rootDir.absolutePath)
    private val serializer = StringSerializer()

    @Test
    fun `put and get record`() = runTest {
        val record = StorageRecord.create("test-value", serializer)
        
        engine.put("key1", record)
        
        val retrieved = engine.get("key1")
        assertNotNull(retrieved)
        assertEquals("test-value", serializer.deserialize(retrieved!!.data))
    }

    @Test
    fun `remove returns true for existing key`() = runTest {
        val record = StorageRecord.create("test-value", serializer)
        engine.put("key1", record)
        
        val removed = engine.remove("key1")
        assertTrue(removed)
        assertNull(engine.get("key1"))
    }

    @Test
    fun `remove returns false for non-existent key`() = runTest {
        val removed = engine.remove("nonexistent")
        assertFalse(removed)
    }

    @Test
    fun `clear removes all records`() = runTest {
        engine.put("key1", StorageRecord.create("value1", serializer))
        engine.put("key2", StorageRecord.create("value2", serializer))
        
        engine.clear()
        
        assertNull(engine.get("key1"))
        assertNull(engine.get("key2"))
    }

    @Test
    fun `size returns correct count`() = runTest {
        assertEquals(0L, engine.size())
        
        engine.put("key1", StorageRecord.create("value1", serializer))
        assertEquals(1L, engine.size())
        
        engine.put("key2", StorageRecord.create("value2", serializer))
        assertEquals(2L, engine.size())
        
        engine.remove("key1")
        assertEquals(1L, engine.size())
    }
}

class StorageRecordTest {

    @Test
    fun `isExpired returns false for null TTL`() {
        val record = StorageRecord(
            data = byteArrayOf(),
            createdAt = System.currentTimeMillis(),
            ttlMillis = null
        )
        assertFalse(record.isExpired())
        assertFalse(record.isExpired(System.currentTimeMillis() + 100000))
    }

    @Test
    fun `isExpired returns true when TTL exceeded`() {
        val now = System.currentTimeMillis()
        val record = StorageRecord(
            data = byteArrayOf(),
            createdAt = now,
            ttlMillis = 1000L // 1 second
        )
        assertFalse(record.isExpired(now))
        assertTrue(record.isExpired(now + 1500))
    }

    @Test
    fun `isExpired returns false within TTL`() {
        val now = System.currentTimeMillis()
        val record = StorageRecord(
            data = byteArrayOf(),
            createdAt = now,
            ttlMillis = 1000L
        )
        assertFalse(record.isExpired(now + 500))
    }

    // @Test
// fun `create serializes value correctly`() = runTest {
//     val serializer = StringSerializer()
//     val record = StorageRecord.create("hello", serializer)
//     
//     assertEquals("hello", serializer.deserialize(record.data))
//     assertNotNull(record.ttlMillis)
// }

    @Test
    fun `create with null TTL sets ttlMillis to null`() = runTest {
        val serializer = StringSerializer()
        val record = StorageRecord.create("hello", serializer, null)
        
        assertNull(record.ttlMillis)
    }
}

class StringSerializerTest {

    @Test
    fun `serializes and deserializes strings`() = runTest {
        val serializer = StringSerializer()
        val original = "hello world"
        
        val bytes = serializer.serialize(original)
        val result = serializer.deserialize(bytes)
        
        assertEquals(original, result)
    }

    @Test
    fun `serializes and deserializes empty string`() = runTest {
        val serializer = StringSerializer()
        val original = ""
        
        val bytes = serializer.serialize(original)
        val result = serializer.deserialize(bytes)
        
        assertEquals(original, result)
    }

    @Test
    fun `serializes and deserializes unicode`() = runTest {
        val serializer = StringSerializer()
        val original = "你好世界 🌍"
        
        val bytes = serializer.serialize(original)
        val result = serializer.deserialize(bytes)
        
        assertEquals(original, result)
    }
}

// KotlinxJsonSerializerTest moved to JVM-only test module
// See KotlinxJsonSerializerJvmTest.kt in jvmTest source set

// L2KmpCache tests moved to JVM-only test module
// See L2KmpCacheJvmTest.kt in jvmTest source set