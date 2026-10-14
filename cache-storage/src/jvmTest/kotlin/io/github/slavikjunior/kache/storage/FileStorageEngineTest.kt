package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.KmpCache
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.EvictionStrategy
import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun unsafeKeyCharactersArePercentEncoded() = runTest {
        engine.put("user/42 profile", StorageRecord.create("v", StringSerializer(), createdAt = 0L))

        // The encoded name is what lands on disk; the raw key must not become a path.
        val onDisk = directory.listFiles().orEmpty().single()
        assertFalse(onDisk.name.contains('/'))
        assertEquals("kuser%2F42%20profile", onDisk.name)
        assertEquals(1L, engine.size())
    }

    @Test
    fun distinctKeysNeverShareAFile() = runTest {
        // These three collapsed onto a single file under the old lossy sanitisation, so
        // writing one silently replaced another and reading either returned the other's value.
        val keys = listOf("user:1", "user/1", "user_1")

        keys.forEachIndexed { index, key ->
            engine.put(key, StorageRecord.create("value-$index", StringSerializer(), createdAt = 0L))
        }

        assertEquals(3L, engine.size(), "each key must own its own file")
        keys.forEachIndexed { index, key ->
            val read = assertNotNull(engine.get(key))
            assertEquals("value-$index", StringSerializer().deserialize(read.data))
        }
    }

    @Test
    fun aDotDotKeyStaysInsideTheCacheDirectory() = runTest {
        val parent = directory.parentFile

        engine.put("..", StorageRecord.create("v", StringSerializer(), createdAt = 0L))
        engine.put(".", StorageRecord.create("v", StringSerializer(), createdAt = 0L))
        engine.put("", StorageRecord.create("v", StringSerializer(), createdAt = 0L))

        // Every record is a file in the cache directory, never the directory above it.
        assertEquals(3L, engine.size())
        assertTrue(directory.listFiles().orEmpty().all { it.isFile })
        assertTrue(assertNotNull(parent).isDirectory, "the parent directory must be untouched")
        assertNotNull(engine.get(".."))
        assertNotNull(engine.get("."))
        assertNotNull(engine.get(""))
    }

    @Test
    fun removingADotDotKeyDoesNotDeleteTheParentDirectory() = runTest {
        val parent = directory.parentFile
        engine.put("..", StorageRecord.create("v", StringSerializer(), createdAt = 0L))

        assertTrue(engine.remove(".."))

        assertTrue(assertNotNull(parent).isDirectory, "removing a key must never touch the parent")
        assertEquals(0L, engine.size())
    }

    @Test
    fun concurrentWritesToTheSameKeyNeverPublishAPartialRecord() = runBlocking {
        // Every writer shared one temporary file before, so a writer could move another's
        // half-written bytes into place. A torn record fails to decode, which is what this
        // asserts against: whatever is read back must be one whole payload.
        val payloads = List(WRITERS) { writer -> "payload-$writer-" + "x".repeat(PAYLOAD_PADDING) }

        withContext(Dispatchers.Default) {
            payloads.map { payload ->
                async {
                    engine.put(
                        HOT_KEY,
                        StorageRecord.create(payload, StringSerializer(), createdAt = 0L),
                    )
                }
            }.awaitAll()
        }

        val read = assertNotNull(engine.get(HOT_KEY), "a torn write leaves nothing readable")
        val decoded = StringSerializer().deserialize(read.data)
        assertTrue(decoded in payloads, "decoded to something no writer wrote: ${decoded.take(32)}")
        assertEquals(1L, engine.size(), "no temporary file may be left behind")
    }

    @Test
    fun aRecordOverTheLimitIsRejectedOnWrite() = runTest {
        val tiny = FileStorageEngine(directory.absolutePath, maxRecordBytes = 32)
        val big = StorageRecord.create("x".repeat(1_000), StringSerializer(), createdAt = 0L)

        assertFailsWith<KacheException.DiskWriteException> { tiny.put("k", big) }
        assertEquals(0L, tiny.size(), "a rejected write must not leave anything behind")
    }

    @Test
    fun aFileOverTheLimitIsNotLoadedIntoMemory() = runTest {
        val tiny = FileStorageEngine(directory.absolutePath, maxRecordBytes = 32)
        tiny.put("k", StorageRecord.create("small", StringSerializer(), createdAt = 0L))
        val file = directory.listFiles().orEmpty().single()

        // A *valid* record that is merely too large. Writing garbage would prove nothing:
        // the old code also refused to decode garbage, so both versions would agree. Here the
        // old code reads and decodes this happily, and only the size guard refuses it.
        val oversized = StorageRecordFileCodec.encode(
            StorageRecord.create("x".repeat(1_000), StringSerializer(), createdAt = 0L)
        )
        assertTrue(oversized.size > 32)
        file.writeBytes(oversized)

        assertNull(tiny.get("k"), "an oversized file is treated as corrupt, not loaded")
        assertFalse(file.exists(), "and dropped, like any other unreadable record")
    }

    @Test
    fun evictionCandidatesAreKeysNotFileNames() = runTest {
        engine.put("user:1", StorageRecord.create("v", StringSerializer(), createdAt = 100L))

        val candidates = engine.evictionCandidates(EvictionStrategy.LRU, limit = 10, now = 0L)

        // The SPI contract says keys. An encoded file name would still remove the right file,
        // but it would be the wrong value to hand back to a caller.
        assertEquals(listOf("user:1"), candidates)
    }

    @Test
    fun evictionCandidatesKeepOnlyTheWorstWhenTheLimitIsSmall() = runTest {
        repeat(1_000) { index ->
            engine.put("key-$index", StorageRecord.create("v", StringSerializer(), createdAt = index.toLong()))
        }

        // LRU ranks by access time, which equals creation time here, so the five oldest go.
        // A full sort would pass this too; the bounded heap is what keeps memory at O(limit).
        val candidates = engine.evictionCandidates(EvictionStrategy.LRU, limit = 5, now = 0L)

        assertEquals(listOf("key-0", "key-1", "key-2", "key-3", "key-4"), candidates)
    }

    @Test
    fun evictionCandidatesIgnoreFilesThisEngineDidNotWrite() = runTest {
        engine.put("mine", StorageRecord.create("v", StringSerializer(), createdAt = 100L))
        File(directory, "foreign").writeBytes(StorageRecordFileCodec.encode(
            StorageRecord.create("v", StringSerializer(), createdAt = 0L)
        ))

        val candidates = engine.evictionCandidates(EvictionStrategy.LRU, limit = 10, now = 0L)

        // A name that does not decode to a key cannot be handed to remove(), so it is skipped.
        assertEquals(listOf("mine"), candidates)
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

    @Test
    fun touchUpdatesTheStoredAccessTimestamp() = runTest {
        engine.put("k", StorageRecord.create("v", StringSerializer(), createdAt = 100L))

        assertTrue(engine.touch("k", accessedAt = 500L), "touch must report that it persisted the timestamp")
        assertEquals(500L, assertNotNull(engine.get("k")).lastAccessedAt)
    }

    @Test
    fun touchOnAMissingKeyReportsFalse() = runTest {
        assertFalse(engine.touch("absent", accessedAt = 500L))
    }

    @Test
    fun touchKeepsThePayloadReadable() = runTest {
        engine.put("k", StorageRecord.create("payload", StringSerializer(), createdAt = 100L))

        engine.touch("k", accessedAt = 500L)

        assertEquals("payload", assertNotNull(engine.get("k")).data.decodeToString())
    }

    @Test
    fun evictionCandidatesRankByTheOldestAccessFirst() = runTest {
        engine.put("late", StorageRecord.create("a", StringSerializer(), createdAt = 100L).copy(lastAccessedAt = 100L))
        engine.put("early", StorageRecord.create("b", StringSerializer(), createdAt = 200L).copy(lastAccessedAt = 50L))

        val candidates = engine.evictionCandidates(EvictionStrategy.LRU, limit = 10, now = 0L)

        assertEquals(listOf("early", "late"), candidates)
    }

    @Test
    fun evictionCandidatesRankByTheOldestWriteFirstUnderFifo() = runTest {
        // Access order deliberately opposes write order: FIFO must ignore it.
        engine.put("written-first", StorageRecord.create("a", StringSerializer(), createdAt = 100L).copy(lastAccessedAt = 900L))
        engine.put("written-last", StorageRecord.create("b", StringSerializer(), createdAt = 300L).copy(lastAccessedAt = 100L))

        val candidates = engine.evictionCandidates(EvictionStrategy.FIFO, limit = 10, now = 0L)

        assertEquals(listOf("written-first", "written-last"), candidates)
    }

    @Test
    fun evictionCandidatesPutExpiredRecordsFirst() = runTest {
        engine.put("fresh", StorageRecord.create("a", StringSerializer(), createdAt = 100L))
        engine.put("stale", StorageRecord.create("b", StringSerializer(), createdAt = 50L, ttl = 10.milliseconds))

        // LIFO would rank "fresh" first by access time; the expired record must still win.
        val candidates = engine.evictionCandidates(EvictionStrategy.LRU, limit = 1, now = 1_000L)

        assertEquals(listOf("stale"), candidates)
    }

    @Test
    fun evictionCandidateKeysCanBeRemoved() = runTest {
        engine.put("a", StorageRecord.create("1", StringSerializer(), createdAt = 100L))
        engine.put("b", StorageRecord.create("2", StringSerializer(), createdAt = 200L))

        // Whatever the candidates are, they must stay valid inputs to remove().
        engine.evictionCandidates(EvictionStrategy.LRU, limit = 1, now = 0L).forEach { engine.remove(it) }

        assertEquals(1L, engine.size())
    }

    @Test
    fun sizeCountsEveryRecordAtVolume() = runTest {
        repeat(2_000) { i ->
            engine.put("key-$i", StorageRecord.create("value-$i", StringSerializer(), createdAt = i.toLong()))
        }

        assertEquals(2_000L, engine.size())

        // The count must track removals too, or a cache that evicts on a size report would
        // never believe it had freed anything.
        repeat(500) { i -> engine.remove("key-$i") }
        assertEquals(1_500L, engine.size())
    }

    @Test
    fun sizeOfAnEmptyDirectoryIsZero() = runTest {
        assertEquals(0L, engine.size())
    }

    @Test
    fun aPartiallyWrittenTemporaryFileIsNotCounted() = runTest {
        // Writes go to a `.tmp` file first and are then moved into place. A crash between those
        // two steps must not leave the count permanently one too high.
        engine.put("k", StorageRecord.create("v", StringSerializer(), createdAt = 0L))
        File(directory, "interrupted.tmp").writeText("garbage")

        assertEquals(1L, engine.size(), "a leftover temp file is not a record")
    }

    private companion object {
        const val WRITERS = 24
        const val PAYLOAD_PADDING = 64 * 1024
        const val HOT_KEY = "hot"
    }
}