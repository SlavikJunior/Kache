package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.EvictionStrategy
import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The file engine under interleaving, on real threads.
 *
 * JVM-only by necessity: `runTest` schedules coroutines on one thread, so it can never produce
 * the interleaving these tests are about. `Dispatchers.Default` is the smallest thing that
 * actually races.
 *
 * What is asserted is the property a cache needs — a reader never observes a partial record,
 * and a crashed or cancelled operation leaves nothing behind that a later read would trip over
 * — not a particular internal order, which no test can pin.
 */
class FileStorageEngineChaosTest {

    private val directory = Files.createTempDirectory("kache-chaos").toFile()
    private val engine = FileStorageEngine(directory.absolutePath)

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun readersNeverObserveAPartialRecordWhileWritersReplaceIt() = runBlocking {
        val payloads = List(WRITERS) { writer -> "payload-$writer-" + "x".repeat(PAYLOAD_PADDING) }
        val serializer = StringSerializer()

        withContext(Dispatchers.Default) {
            val writers = payloads.map { payload ->
                async {
                    repeat(ROUNDS) {
                        engine.put(HOT_KEY, StorageRecord.create(payload, serializer, createdAt = 0L))
                    }
                }
            }
            val readers = List(READERS) {
                async {
                    repeat(ROUNDS * 4) {
                        val record = engine.get(HOT_KEY) ?: return@repeat
                        val decoded = serializer.deserialize(record.data)
                        assertTrue(
                            decoded in payloads,
                            "a reader saw ${decoded.take(24)}…, which no writer wrote",
                        )
                    }
                }
            }

            (writers + readers).awaitAll()
        }

        assertEquals(1L, engine.size(), "no temporary file may survive the race")
    }

    @Test
    fun clearingWhileWritesAreInFlightLeavesOnlyDecodableRecords() = runBlocking {
        var failedWrites = 0

        withContext(Dispatchers.Default) {
            val writers = List(WRITERS) { writer ->
                async {
                    repeat(ROUNDS) {
                        try {
                            engine.put("k$writer", StorageRecord.create("v$writer", StringSerializer(), createdAt = 0L))
                        } catch (e: io.github.slavikjunior.kache.core.KacheException.DiskWriteException) {
                            // Documented: `clear` deletes a writer's temporary file, so that
                            // write fails instead of being silently dropped.
                            failedWrites++
                        }
                    }
                }
            }
            val clearer = async { repeat(ROUNDS) { engine.clear() } }

            (writers + listOf(clearer)).awaitAll()
            Unit
        }

        // Whatever survived must be readable: `clear` deletes files while writers rename new
        // ones into place, so a surviving file could be anything the platform left behind.
        val survivors = directory.listFiles().orEmpty().filter { it.isFile }
        survivors.forEach { file ->
            assertTrue(
                !file.name.endsWith(".tmp"),
                "${file.name} is a temporary file: a failed write must clean up after itself",
            )
            val decoded = StorageRecordFileCodec.decode(file.readBytes())
            assertTrue(decoded.data.isNotEmpty(), "${file.name} survived but holds an empty record")
        }
        assertEquals(survivors.size.toLong(), engine.size(), "size must agree with the directory")
        // `failedWrites` is deliberately not asserted on: whether the race produces a failing
        // write depends on scheduling, and a test that demands it is a coin flip. The
        // deterministic version of that case lives in the contract test, where the write is
        // made to fail on purpose.
    }

    @Test
    fun rankingRunsWhileWritesAreInFlightWithoutThrowing() = runBlocking {
        withContext(Dispatchers.Default) {
            val writers = List(WRITERS) { writer ->
                async {
                    repeat(ROUNDS) {
                        engine.put("k$writer", StorageRecord.create("v$writer", StringSerializer(), createdAt = 0L))
                    }
                }
            }
            val ranker = async {
                repeat(ROUNDS) {
                    val candidates = engine.evictionCandidates(
                        strategy = EvictionStrategy.LRU,
                        limit = 4,
                        now = 0L,
                    )
                    // A candidate may be deleted between the ranking and this check by `clear`
                    // in another test, but within one run nothing removes keys: whatever is
                    // returned has to be a key some writer actually used.
                    assertTrue(candidates.size <= 4, "a bounded request returned ${candidates.size}")
                    assertTrue(candidates.all { it.startsWith("k") }, "candidates must be keys, not names")
                }
            }

            (writers + listOf(ranker)).awaitAll()
            Unit
        }
    }

    @Test
    fun aRefusedOversizedWriteLeavesNoTemporaryFile() = runBlocking {
        val tiny = FileStorageEngine(directory.absolutePath, maxRecordBytes = 64)

        repeat(ROUNDS) {
            runCatching { tiny.put(HOT_KEY, StorageRecord.create("x".repeat(1_000), StringSerializer(), createdAt = 0L)) }
        }

        val names = directory.listFiles().orEmpty().map(File::getName)
        assertTrue(names.isEmpty(), "a refused write must not leave temp files: $names")
    }

    @Test
    fun evictionDuringWritesNeverLeavesAnUnreadableRecord() = runBlocking {
        // The cache layer evicts while writers keep inserting, so files disappear underneath a
        // reader. What must hold is that a record is either fully there or absent — never
        // truncated — and that capacity is actually enforced.
        val cache = L2KmpCache<String, String>(
            storageEngine = FileStorageEngine(directory.absolutePath),
            valueSerializer = StringSerializer(),
            keyToString = { it },
            maxSize = 8,
            evictionStrategy = EvictionStrategy.LRU,
        )

        withContext(Dispatchers.Default) {
            List(WRITERS) { writer ->
                async {
                    repeat(ROUNDS) { round ->
                        val key = "k$writer-$round"
                        cache.put(key, "value-$writer-$round")
                        // A read after the write either finds this key's whole value (it may
                        // have been evicted by another writer in between, which is a miss, not
                        // a wrong value).
                        when (val result = cache.get(key, CacheStrategy.CacheFirst).first()) {
                            is CacheResult.Success -> assertTrue(
                                result.data.startsWith("value-$writer-$round"),
                                "a key returned another key's value: cross-key corruption",
                            )
                            is CacheResult.Error -> Unit
                        }
                    }
                }
            }.awaitAll()
            Unit
        }

        assertTrue(engine.size() <= 8, "capacity must be enforced under concurrent writes, was ${engine.size()}")
    }

    private companion object {
        const val HOT_KEY = "hot"
        const val WRITERS = 8
        const val READERS = 4
        const val ROUNDS = 25
        const val PAYLOAD_PADDING = 400
    }
}
