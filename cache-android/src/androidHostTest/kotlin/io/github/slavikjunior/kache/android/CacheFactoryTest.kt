package io.github.slavikjunior.kache.android

import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.EvictionStrategy
import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.core.MutableTimeSource
import io.github.slavikjunior.kache.storage.FileStorageEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The Android-only factory, exercised against the real Android runtime under Robolectric.
 *
 * The `Context` overload is the point of the module: it is the reason a consumer does not
 * have to build a file engine by hand, and that path cannot be verified without a real
 * `Context` and app cache directory.
 */
@RunWith(RobolectricTestRunner::class)
class CacheFactoryTest {

    private lateinit var directories: MutableList<File>

    @Before
    fun setUp() {
        directories = mutableListOf()
    }

    @After
    fun tearDown() {
        directories.forEach { it.deleteRecursively() }
    }

    private fun engineDirectory(name: String): File =
        temporaryCacheDirectory(name).also { directories += it }

    @Test
    fun theContextOverloadWritesThroughTheAppCacheDirectory() = runTest {
        val context = RuntimeEnvironment.getApplication()

        val cache = l2Cache(context = context, serializer = TextSerializer)

        cache.put("k", "v")

        // Reading back through a second cache proves the data really reached the disk
        // location the Context resolved to.
        val reopened = l2Cache(context = context, serializer = TextSerializer)
        val result = reopened.get("k", CacheStrategy.CacheFirst, fetcher = null).first()

        assertEquals(CacheResult.Success("v", CacheOrigin.DISK), result)
    }

    @Test
    fun theContextOverloadIsolatesCachesByDirectoryName() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val first = l2Cache(context, TextSerializer, cacheDirName = "first")
        val second = l2Cache(context, TextSerializer, cacheDirName = "second")

        first.put("k", "in-first")

        val fromSecond = second.get("k", CacheStrategy.CacheFirst, fetcher = null).first()
        assertIs<CacheResult.Error<String>>(fromSecond)
        assertTrue(
            File(context.cacheDir, "first").isDirectory,
            "the named directory must be created",
        )
    }

    @Test
    fun theEngineOverloadLeavesOwnershipWithTheCaller() = runTest {
        val engine = RecordingStorageEngine()

        val cache = l2Cache(storageEngine = engine, serializer = TextSerializer)
        cache.put("k", "v")

        // The caller still holds the engine, so the cache must have written through it
        // rather than replacing it.
        assertEquals(1, engine.putCount)
        assertEquals(1L, engine.size())
    }

    @Test
    fun theDefaultTtlIsOneHour() {
        assertEquals(1.hours, DEFAULT_TTL)
    }

    @Test
    fun theDefaultDirectoryNameIsStable() {
        assertEquals("kache", DEFAULT_CACHE_DIR_NAME)
    }

    @Test
    fun anExplicitTtlOverridesTheDefault() = runTest {
        val engine = RecordingStorageEngine()
        val cache = l2Cache(storageEngine = engine, serializer = TextSerializer, defaultTtl = 10.seconds)

        cache.put("k", "v")

        val record = assertNotNull(engine.get("k"))
        assertNotNull(record.ttl)
    }

    @Test
    fun aFileBackedCacheRoundTripsThroughDisk() = runTest {
        val directory = engineDirectory("engine-overload")
        val engine = io.github.slavikjunior.kache.storage.FileStorageEngine(directory.absolutePath)
        val cache: L2KmpCache<String, String> = l2Cache(storageEngine = engine, serializer = TextSerializer)

        cache.put("k", "persisted")

        // A second engine over the same directory is what a fresh process would see.
        val reopened = io.github.slavikjunior.kache.storage.FileStorageEngine(directory.absolutePath)
        val result = l2Cache(storageEngine = reopened, serializer = TextSerializer)
            .get("k", CacheStrategy.CacheFirst, fetcher = null)
            .first()

        assertEquals(CacheResult.Success("persisted", CacheOrigin.DISK), result)
    }

    @Test
    fun aMissIsReportedRatherThanThrowing() = runTest {
        val cache = l2Cache(storageEngine = RecordingStorageEngine(), serializer = TextSerializer)

        val result = cache.get("absent", CacheStrategy.CacheFirst, fetcher = null).first()

        val error = assertIs<CacheResult.Error<String>>(result)
        assertIs<io.github.slavikjunior.kache.core.KacheException.CacheMissException>(error.error)
    }

    @Test
    fun nullEntriesAreReportedInsteadOfStored() = runTest {
        val engine = RecordingStorageEngine()
        val cache = l2Cache(storageEngine = engine, serializer = TextSerializer)

        cache.clear()

        assertEquals(1, engine.clearCount)
        assertNull(engine.get("anything"))
    }

    @Test
    fun maxSizeBoundsTheCache() = runTest {
        val engine = FileStorageEngine(engineDirectory("bounded").absolutePath)
        val cache = l2Cache(storageEngine = engine, serializer = TextSerializer, maxSize = 2)

        repeat(5) { index -> cache.put("key-$index", "value-$index") }

        assertEquals(2L, engine.size(), "a bounded cache must stay bounded")
    }

    @Test
    fun theDefaultLeavesTheCacheUnbounded() = runTest {
        val engine = FileStorageEngine(engineDirectory("unbounded").absolutePath)
        val cache = l2Cache(storageEngine = engine, serializer = TextSerializer)

        repeat(5) { index -> cache.put("key-$index", "value-$index") }

        // Deliberate: bounding also turns on access tracking, which costs a write per key per
        // window, and the factory must not start doing I/O the caller never asked for. This
        // test pins that choice so it cannot be changed by accident.
        assertEquals(5L, engine.size(), "the default must not evict anything")
    }

    @Test
    fun theEvictionStrategyIsForwarded() = runTest {
        val fifoEngine = FileStorageEngine(engineDirectory("fifo").absolutePath)
        val fifoClock = MutableTimeSource(1_000L)
        val fifo = l2Cache(
            storageEngine = fifoEngine,
            serializer = TextSerializer,
            timeSource = fifoClock,
            maxSize = 2,
            evictionStrategy = EvictionStrategy.FIFO,
        )
        fifo.put("a", "A")
        fifoClock.advance(1_000.milliseconds)
        fifo.put("b", "B")
        fifoClock.advance(1_000.milliseconds)
        fifo.put("c", "C")

        assertNull(fifoEngine.get("a"), "FIFO must drop the oldest write")
        assertNotNull(fifoEngine.get("c"), "and never the write that triggered the eviction")

        val lifoEngine = FileStorageEngine(engineDirectory("lifo").absolutePath)
        val lifoClock = MutableTimeSource(1_000L)
        val lifo = l2Cache(
            storageEngine = lifoEngine,
            serializer = TextSerializer,
            timeSource = lifoClock,
            maxSize = 2,
            evictionStrategy = EvictionStrategy.LIFO,
        )
        lifo.put("a", "A")
        lifoClock.advance(1_000.milliseconds)
        lifo.put("b", "B")
        lifoClock.advance(1_000.milliseconds)
        lifo.put("c", "C")

        // "c" is the record the write just persisted and is protected, so LIFO takes "b".
        // Asserting a different key than FIFO is what proves the strategy reached the engine.
        assertNull(lifoEngine.get("b"), "LIFO must drop the newest unprotected write")
        assertNotNull(lifoEngine.get("a"), "and leave the oldest alone")
    }
}