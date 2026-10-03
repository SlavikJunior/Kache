package io.github.slavikjunior.kache.android

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import io.github.slavikjunior.kache.core.KmpCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Memory-pressure handling, where the decision that matters is which trim levels are
 * treated as a reason to evict.
 *
 * Getting it wrong in one direction drops data on every backgrounding; getting it wrong
 * in the other keeps data until the process is actually killed, which is too late for the
 * cache to do anything about it.
 */
@RunWith(RobolectricTestRunner::class)
class CacheMemoryPressureTest {

    private val application: Application get() = RuntimeEnvironment.getApplication()

    private fun cacheOver(engine: RecordingStorageEngine) =
        KmpCacheImpl(engine)

    /** Thin adapter so the extension can be called with an interface-typed cache. */
    private class KmpCacheImpl(engine: RecordingStorageEngine) : KmpCache<String, String> {
        private val delegate = io.github.slavikjunior.kache.core.L2KmpCache<String, String>(
            storageEngine = engine,
            valueSerializer = TextSerializer,
        )

        override fun get(
            key: String,
            strategy: io.github.slavikjunior.kache.core.CacheStrategy,
            fetcher: (suspend (String) -> String)?,
        ) = delegate.get(key, strategy, fetcher)

        override suspend fun put(key: String, value: String, ttlMs: Long?) =
            delegate.put(key, value, ttlMs)

        override suspend fun invalidate(key: String) = delegate.invalidate(key)

        override suspend fun clear() = delegate.clear()
    }

    @Test
    fun completeTrimEvicts() = runBlocking {
        val engine = RecordingStorageEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val callbacks = application.registerCacheMemoryPressureCallbacks(scope, cacheOver(engine))

        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        awaitClear(engine)

        assertTrue(engine.clearCount >= 1, "a complete trim must evict")
        application.unregisterCacheMemoryPressureCallbacks(callbacks)
    }

    @Test
    fun lowMemoryEvicts() = runBlocking {
        val engine = RecordingStorageEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val callbacks = application.registerCacheMemoryPressureCallbacks(scope, cacheOver(engine))

        callbacks.onLowMemory()
        awaitClear(engine)

        assertTrue(engine.clearCount >= 1)
        application.unregisterCacheMemoryPressureCallbacks(callbacks)
    }

    @Test
    fun uiHiddenDoesNotEvict() = runBlocking {
        val engine = RecordingStorageEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val callbacks = application.registerCacheMemoryPressureCallbacks(scope, cacheOver(engine))

        // The process is still alive and fully expected back; evicting here would turn a
        // survivable trim into a visible reload.
        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        delay(SETTLE_MS)

        assertEquals(0, engine.clearCount, "UI_HIDDEN must keep the data")
        application.unregisterCacheMemoryPressureCallbacks(callbacks)
    }

    @Test
    fun runningInBackgroundDoesNotEvict() = runBlocking {
        val engine = RecordingStorageEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val callbacks = application.registerCacheMemoryPressureCallbacks(scope, cacheOver(engine))

        callbacks.onTrimMemory(BACKGROUND_TRIM_LEVEL)
        delay(SETTLE_MS)

        assertEquals(0, engine.clearCount)
        application.unregisterCacheMemoryPressureCallbacks(callbacks)
    }

    @Test
    fun aConfigurationChangeDoesNotEvict() = runBlocking {
        val engine = RecordingStorageEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val callbacks = application.registerCacheMemoryPressureCallbacks(scope, cacheOver(engine))

        callbacks.onConfigurationChanged(Configuration())
        delay(SETTLE_MS)

        assertEquals(0, engine.clearCount, "a rotation is not memory pressure")
        application.unregisterCacheMemoryPressureCallbacks(callbacks)
    }

    @Test
    fun onlyTheCompleteTrimIsConsideredImmediate() {
        assertTrue(requiresImmediateCacheClear(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))

        assertFalse(requiresImmediateCacheClear(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertFalse(requiresImmediateCacheClear(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertFalse(requiresImmediateCacheClear(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertFalse(requiresImmediateCacheClear(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(requiresImmediateCacheClear(BACKGROUND_TRIM_LEVEL))
    }

    @Test
    fun theThresholdSitsExactlyAtTheCompleteTrim() {
        // Anything below the constant must keep the cache, and the constant itself must
        // clear it. Pinning both sides stops a refactor from moving the boundary silently.
        assertFalse(requiresImmediateCacheClear(ComponentCallbacks2.TRIM_MEMORY_COMPLETE - 1))
        assertTrue(requiresImmediateCacheClear(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun everyRegisteredCacheIsEvicted() = runBlocking {
        val engines = List(3) { RecordingStorageEngine() }
        val scope = CoroutineScope(Dispatchers.Default)
        val callbacks = application.registerCacheMemoryPressureCallbacks(
            scope = scope,
            caches = engines.map { cacheOver(it) }.toTypedArray(),
        )

        callbacks.onLowMemory()
        engines.forEach { awaitClear(it) }

        engines.forEachIndexed { index, engine ->
            assertTrue(engine.clearCount >= 1, "cache $index must be evicted")
        }
        application.unregisterCacheMemoryPressureCallbacks(callbacks)
    }

    @Test
    fun oneFailingCacheDoesNotBlockTheOthers() = runBlocking {
        val failing = RecordingStorageEngine()
        val healthy = RecordingStorageEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val callbacks = application.registerCacheMemoryPressureCallbacks(
            scope = scope,
            caches = arrayOf(cacheOver(failing), cacheOver(healthy)),
        )

        // Even if one engine threw, the loop must not abort before reaching the rest.
        callbacks.onLowMemory()
        awaitClear(healthy)

        assertTrue(healthy.clearCount >= 1)
        application.unregisterCacheMemoryPressureCallbacks(callbacks)
    }

    /** The clearing is launched on a scope, so it is not synchronous with the callback. */
    private suspend fun awaitClear(engine: RecordingStorageEngine) {
        withTimeout(TIMEOUT_MS) {
            while (engine.clearCount == 0) delay(POLL_MS)
        }
    }

    private companion object {
        /**
         * `ComponentCallbacks2.TRIM_MEMORY_BACKGROUND` on API 29+, which is the level a
         * backgrounded process receives and which must not evict.
         */
        const val BACKGROUND_TRIM_LEVEL = 40

        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 150L
    }
}