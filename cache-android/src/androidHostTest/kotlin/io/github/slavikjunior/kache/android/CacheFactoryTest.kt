package io.github.slavikjunior.kache.android

import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.L2KmpCache
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
}