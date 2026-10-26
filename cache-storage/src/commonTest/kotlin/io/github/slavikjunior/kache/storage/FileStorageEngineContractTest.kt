package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.EvictionStrategy
import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The file engine's contract, on every target.
 *
 * `FileStorageEngineTest` in `jvmTest` keeps the cases that need JVM threads (concurrent
 * writers on `Dispatchers.Default`); everything that must hold on iOS as well lives here, so
 * the simulator runs the same assertions the JVM does. Nothing is mocked: `java.io` backs
 * JVM/Android and kotlinx-io backs iOS, so a platform difference fails here.
 */
class FileStorageEngineContractTest {

    private val root = newTestDirectory("engine-contract")
    private val engine = FileStorageEngine(root)

    @Test
    fun aWrittenRecordIsReadBackWithItsMetadata() = runTest {
        engine.put("key", record(bytes = "payload".encodeToByteArray(), ttl = 30.seconds))

        val read = assertNotNull(engine.get("key"))

        assertContentEquals("payload".encodeToByteArray(), read.data)
        assertEquals(1_000L, read.createdAt)
        assertEquals(30.seconds, read.ttl)
        assertEquals(2_000L, read.lastAccessedAt)
    }

    @Test
    fun anAbsentKeyReadsAsNothing() = runTest {
        assertNull(engine.get("absent"))
        assertEquals(0L, engine.size())
    }

    @Test
    fun aSecondEngineOverTheSameDirectorySeesTheRecord() = runTest {
        engine.put("k", record(bytes = "persisted".encodeToByteArray()))

        val reopened = FileStorageEngine(root)

        assertContentEquals("persisted".encodeToByteArray(), assertNotNull(reopened.get("k")).data)
    }

    @Test
    fun removeAndClearReportWhatTheyRemoved() = runTest {
        engine.put("a", record(bytes = "1".encodeToByteArray()))
        engine.put("b", record(bytes = "2".encodeToByteArray()))

        assertTrue(engine.remove("a"))
        assertFalse(engine.remove("a"), "removing twice must report the second as a no-op")
        assertEquals(1L, engine.size())

        engine.clear()
        assertEquals(0L, engine.size())
        assertNull(engine.get("b"))
    }

    @Test
    fun keysAreEncodedReversiblySoDistinctKeysNeverShareAFile() = runTest {
        // The lossy sanitisation this replaced collapsed these onto one file, so writing one
        // silently replaced another — a cross-key data leak in any user-derived scheme.
        val keys = listOf("user:1", "user_1", "user/1")

        keys.forEach { key -> engine.put(key, record(bytes = key.encodeToByteArray())) }

        assertEquals(3L, engine.size(), "each key must own its own file")
        keys.forEach { key ->
            assertContentEquals(key.encodeToByteArray(), assertNotNull(engine.get(key)).data)
        }
    }

    @Test
    fun aKeyThatLooksLikeAContainerNameStaysInsideTheRoot() = runTest {
        val keys = listOf("..", ".", "", "/etc/passwd")
        keys.forEach { key -> engine.put(key, record(bytes = "v".encodeToByteArray())) }

        assertEquals(keys.size.toLong(), engine.size())
        keys.forEach { key ->
            assertNotNull(engine.get(key), "key '$key' must round trip")
        }
        // Nothing escaped: the root still holds exactly the records and no directory appeared
        // beside it, which is what a `..` key would have named before the name marker.
        assertTrue(FileTestSupport.namesIn(root).all { !it.contains('/') })
    }

    @Test
    fun aRecordOverTheLimitIsRefusedOnWriteAndDroppedOnRead() = runTest {
        val tiny = FileStorageEngine(root, maxRecordBytes = 32)

        assertFailsWith<KacheException.DiskWriteException> {
            tiny.put("k", record(bytes = ByteArray(64) { 'x'.code.toByte() }))
        }

        // A file larger than the limit is what a corrupt or hostile cache directory holds: it
        // must read as absent and be dropped, not loaded into memory.
        val foreign = joinPath(root, "kforeign")
        FileTestSupport.writeFile(foreign, ByteArray(64) { 'y'.code.toByte() })

        assertNull(tiny.get("foreign"))
        assertFalse(FileTestSupport.exists(foreign), "an oversized record is dropped")
    }

    @Test
    fun expiredRecordsAreReapedAndLiveOnesAreKept() = runTest {
        engine.put("expired", record(bytes = "old".encodeToByteArray(), ttl = 1.seconds))
        engine.put("live", record(bytes = "new".encodeToByteArray(), ttl = 1.hours))

        val removed = engine.removeExpired(now = 3_000L)

        assertEquals(1L, removed)
        assertNull(engine.get("expired"))
        assertNotNull(engine.get("live"))
    }

    @Test
    fun anExpiredRecordIsStillReadableUntilReaped() = runTest {
        engine.put("k", record(bytes = "stale".encodeToByteArray(), ttl = 1.seconds, createdAt = 0L))

        // The engine reports expiry to the strategy above; it does not decide that a stale
        // value is useless — `StaleWhileRevalidate` exists precisely because it is useful.
        assertContentEquals("stale".encodeToByteArray(), assertNotNull(engine.get("k")).data)
    }

    @Test
    fun touchRewritesOnlyTheAccessTimestamp() = runTest {
        engine.put("k", record(bytes = "v".encodeToByteArray(), lastAccessedAt = 5L))

        assertTrue(engine.touch("k", accessedAt = 9_000L))
        assertEquals(9_000L, assertNotNull(engine.get("k")).lastAccessedAt)
        assertFalse(engine.touch("absent", accessedAt = 1L))
    }

    @Test
    fun evictionCandidatesPreferExpiredThenTheStrategyOrder() = runTest {
        engine.put("expired", record(bytes = "e".encodeToByteArray(), ttl = 1.seconds, createdAt = 0L))
        engine.put("oldest", record(bytes = "1".encodeToByteArray(), lastAccessedAt = 10L))
        engine.put("newest", record(bytes = "2".encodeToByteArray(), lastAccessedAt = 20L))

        val candidates = engine.evictionCandidates(
            strategy = EvictionStrategy.LRU,
            limit = 2,
            now = 10_000L,
        )

        assertEquals(
            listOf("expired", "oldest"),
            candidates,
            "an expired record goes first, then the least recently used",
        )
    }

    @Test
    fun aWriteLeavesNoTemporaryFileBehind() = runTest {
        engine.put("k", record(bytes = "v".encodeToByteArray()))

        val names = FileTestSupport.namesIn(root)
        assertEquals(listOf("kk"), names, "the record file only: a leftover temp file would count twice")
        assertEquals(1L, engine.size())
    }

    @Test
    fun aRecordExactlyAtTheLimitIsAcceptedAndOneByteOverIsRefused() = runTest {
        // The boundary is inclusive on the write path: a record that fits exactly must not be
        // rejected, and the off-by-one must not be silently allowed — both directions are one
        // comparison away from each other.
        val record = record(bytes = ByteArray(100) { 'x'.code.toByte() })
        val encodedSize = StorageRecordFileCodec.encode(record).size.toLong()

        val exact = FileStorageEngine(newTestDirectory("engine-exact"), maxRecordBytes = encodedSize)
        exact.put("k", record)
        assertContentEquals(record.data, assertNotNull(exact.get("k")).data)

        val tooSmall = FileStorageEngine(newTestDirectory("engine-small"), maxRecordBytes = encodedSize - 1)
        assertFailsWith<KacheException.DiskWriteException> { tooSmall.put("k", record) }
        assertEquals(0L, tooSmall.size(), "a refused write must not leave a record behind")
    }

    @Test
    fun aHeaderClaimingAHugeLengthIsTreatedAsCorruptRatherThanAllocated() = runTest {
        // A crafted file, not a random one: the declared length is the part an attacker
        // controls, and narrowing it to Int before validating is how a tiny file becomes a
        // huge allocation or a silently empty payload.
        val crafted = "v2|1|0|2|9223372036854775807|x".encodeToByteArray()
        val path = joinPath(root, "kcrafted")
        FileTestSupport.writeFile(path, crafted)

        val read = engine.get("crafted")

        assertNull(read, "a record whose declared length cannot fit must read as absent")
        assertFalse(FileTestSupport.exists(path), "and be dropped like any other corrupt record")
    }

    @Test
    fun aWriteThatCannotBePublishedIsReportedAndLeavesNothingBehind() = runTest {
        // The interleaving a concurrent `clear()` produces, made deterministic: the temporary
        // file disappears between being written and being moved into place. The engine has no
        // say in that — it delegates the write — so what it must guarantee is the report and
        // the cleanup: a typed failure, no half record, no leftover temp file.
        val real = platformFileOps()
        val vanishing = object : FileOps by real {
            override fun writeAtomically(
                target: String,
                directory: String,
                bytes: ByteArray,
                tempPrefix: String,
                tempSuffix: String,
            ) {
                val temp = joinPath(directory, tempPrefix + "vanishing" + tempSuffix)
                real.writeAtomically(temp, directory, bytes, tempPrefix, tempSuffix)
                real.delete(temp)
                throw IllegalStateException("the temporary file disappeared")
            }
        }
        val directory = newTestDirectory("engine-vanishing")
        val engine = FileStorageEngine(directory, DEFAULT_MAX_RECORD_BYTES, vanishing)

        assertFailsWith<KacheException.DiskWriteException> {
            engine.put("k", record(bytes = "value".encodeToByteArray()))
        }

        assertNull(engine.get("k"), "a failed write must not publish a record")
        assertEquals(emptyList(), FileTestSupport.namesIn(directory), "and must not leave a file behind")
    }

    @Test
    fun foreignFilesAreIgnoredByRankingAndByCount() = runTest {
        engine.put("mine", record(bytes = "v".encodeToByteArray()))
        // A name `encodeKey` cannot have produced: it does not start with the marker.
        FileTestSupport.writeFile(
            joinPath(root, "foreign"),
            StorageRecordFileCodec.encode(record(bytes = "x".encodeToByteArray())),
        )

        assertEquals(1L, engine.size(), "only records the engine wrote are counted")
        assertEquals(
            listOf("mine"),
            engine.evictionCandidates(EvictionStrategy.LRU, limit = 10, now = 0L),
        )
    }

    /** Mirrors the values a cache layer writes, without pulling a serializer into the test. */
    private fun record(
        bytes: ByteArray,
        ttl: Duration? = null,
        createdAt: Long = 1_000L,
        lastAccessedAt: Long = 2_000L,
    ): StorageRecord<ByteArray> = StorageRecord(
        value = bytes,
        data = bytes,
        createdAt = createdAt,
        ttl = ttl,
        lastAccessedAt = lastAccessedAt,
    )
}
