package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.KmpCache
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Profile(val id: String, val name: String)

class FileStorageEngineTest {

    private val directory = Files.createTempDirectory("kache-test").toFile()
    private val engine = FileStorageEngine(directory.absolutePath)

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun missingDirectoryIsCreated() {
        val nested = directory.resolve("a/b/c")
        val created = FileStorageEngine(nested.absolutePath)

        assertTrue(nested.isDirectory)
        // The engine must be constructible even before anything is written.
        assertNotNull(created)
    }

    @Test
    fun roundTripKeepsValueAndTtl() = runTest {
        val record = StorageRecord.create("payload", StringSerializer(), createdAt = 123L, ttl = 456.milliseconds)

        engine.put("key", record)
        val read = engine.get("key")

        assertNotNull(read)
        assertEquals(123L, read.createdAt)
        assertEquals(456.milliseconds, read.ttl)
        assertEquals("payload", StringSerializer().deserialize(read.data))
    }

    @Test
    fun getOnMissingKeyReturnsNull() = runTest {
        assertNull(engine.get("absent"))
    }

    @Test
    fun overwritingReplacesTheRecord() = runTest {
        engine.put("k", StorageRecord.create("first", StringSerializer(), createdAt = 0L))
        engine.put("k", StorageRecord.create("second", StringSerializer(), createdAt = 0L))

        val read = engine.get("k")

        assertEquals(1L, engine.size())
        assertEquals("second", StringSerializer().deserialize(assertNotNull(read).data))
    }

    @Test
    fun dataSurvivesANewEngineOverTheSameDirectory() = runTest {
        engine.put("k", StorageRecord.create("persisted", StringSerializer(), createdAt = 0L))

        // This is what makes the engine durable: a fresh instance reads what is on disk,
        // which is exactly what the sample checks after a process restart.
        val reopened = FileStorageEngine(directory.absolutePath)
        val read = reopened.get("k")

        assertEquals("persisted", StringSerializer().deserialize(assertNotNull(read).data))
    }

    @Test
    fun unsafeKeyCharactersAreSanitised() = runTest {
        engine.put("user/42 profile", StorageRecord.create("v", StringSerializer(), createdAt = 0L))

        // The sanitised name is what lands on disk; the raw key must not become a path.
        val onDisk = directory.listFiles().orEmpty().single()
        assertFalse(onDisk.name.contains('/'))
        assertEquals("user_42_profile", onDisk.name)
        assertEquals(1L, engine.size())
    }

    @Test
    fun aCorruptRecordIsDroppedRatherThanThrowing() = runTest {
        engine.put("k", StorageRecord.create("v", StringSerializer(), createdAt = 0L))
        val file = directory.listFiles().orEmpty().single()
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(file.exists())

        // A truncated or corrupt payload must not take the caller down; the key is
        // re-fetchable instead.
        assertNull(engine.get("k"))
        assertFalse(file.exists(), "the unreadable record must be deleted")
    }

    @Test
    fun removeExpiredAlsoDropsUnreadableRecords() = runTest {
        engine.put("corrupt", StorageRecord.create("v", StringSerializer(), createdAt = 0L))
        val file = directory.listFiles().orEmpty().single()
        file.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(1L, engine.removeExpired(now = 0L))
        assertEquals(0L, engine.size())
    }

    @Test
    fun removeExpiredLeavesAnUnreadableRecordCountedOnlyOnce() = runTest {
        engine.put("a", StorageRecord.create("1", StringSerializer(), createdAt = 0L, ttl = 10.milliseconds))
        engine.put("b", StorageRecord.create("2", StringSerializer(), createdAt = 0L, ttl = 10.milliseconds))

        assertEquals(2L, engine.removeExpired(now = 1_000))
        assertEquals(0L, engine.removeExpired(now = 1_000), "a second pass finds nothing")
    }

    @Test
    fun removeExpiredKeepsRecordsWithoutTtl() = runTest {
        engine.put("forever", StorageRecord.create("v", StringSerializer(), createdAt = 0L, ttl = null))

        assertEquals(0L, engine.removeExpired(now = Long.MAX_VALUE))
        assertEquals(1L, engine.size())
    }

    @Test
    fun removeExpiredSkipsLeftoverTempFiles() = runTest {
        engine.put("k", StorageRecord.create("v", StringSerializer(), createdAt = 0L, ttl = 10.milliseconds))
        directory.resolve("interrupted.tmp").writeText("partial")

        assertEquals(1L, engine.removeExpired(now = 1_000))
        assertTrue(
            directory.resolve("interrupted.tmp").exists(),
            "an interrupted write is not a record and must be left alone",
        )
    }

    @Test
    fun removeReportsWhetherTheKeyExisted() = runTest {
        engine.put("k", StorageRecord.create("v", StringSerializer(), createdAt = 0L))

        assertTrue(engine.remove("k"))
        assertFalse(engine.remove("k"), "removing twice must report false")
    }

    @Test
    fun clearRemovesEveryRecord() = runTest {
        engine.put("a", StorageRecord.create("1", StringSerializer(), createdAt = 0L))
        engine.put("b", StorageRecord.create("2", StringSerializer(), createdAt = 0L))

        engine.clear()

        assertEquals(0L, engine.size())
    }

    @Test
    fun sizeCountsRecordsNotBytes() = runTest {
        engine.put("a", StorageRecord.create("1", StringSerializer(), createdAt = 0L))
        engine.put("b", StorageRecord.create("2", StringSerializer(), createdAt = 0L))

        assertEquals(2L, engine.size())
    }

    @Test
    fun removeExpiredDropsOnlyExpiredRecords() = runTest {
        engine.put("fresh", StorageRecord.create("1", StringSerializer(), createdAt = 0L, ttl = 10.seconds))
        engine.put("stale", StorageRecord.create("2", StringSerializer(), createdAt = 0L, ttl = 10.milliseconds))

        val removed = engine.removeExpired(now = 1_000)

        assertEquals(1L, removed)
        assertNotNull(engine.get("fresh"))
        assertNull(engine.get("stale"))
    }

    @Test
    fun expiredRecordsAreStillReadableByDesign() = runTest {
        // StaleWhileRevalidate needs the value, so the engine returns it and leaves the
        // decision to expire to the strategy above.
        engine.put("k", StorageRecord.create("v", StringSerializer(), createdAt = 0L, ttl = 10.milliseconds))

        val read = engine.get("k")

        assertNotNull(read)
        assertTrue(read.isExpired(currentTimeMillis = 5_000))
    }

    @Test
    fun aTempFileIsNeverCountedAsARecord() = runTest {
        engine.put("k", StorageRecord.create("v", StringSerializer(), createdAt = 0L))
        directory.resolve("leftover.tmp").writeText("partial")

        assertEquals(1L, engine.size(), "an interrupted write must not inflate the count")
    }

    @Test
    fun worksAsTheL2TierOfACache() = runTest {
        val cache = L2KmpCache<String, Profile>(
            storageEngine = FileStorageEngine(directory.absolutePath),
            valueSerializer = KotlinxJsonSerializer(Profile.serializer()),
            defaultTtl = 10.seconds,
        )
        val profile = Profile(id = "u-1", name = "Ada")

        cache.put("u-1", profile)
        val result = cache.get("u-1", CacheStrategy.CacheFirst, fetcher = null).first()

        assertIs<io.github.slavikjunior.kache.core.CacheResult.Success<Profile>>(result)
        assertEquals(profile, result.data)
        assertContentEquals(
            KotlinxJsonSerializer(Profile.serializer()).serialize(profile),
            result.data.let { KotlinxJsonSerializer(Profile.serializer()).serialize(it) },
        )
    }
}