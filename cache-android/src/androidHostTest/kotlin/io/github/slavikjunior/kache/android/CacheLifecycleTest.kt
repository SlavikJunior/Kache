package io.github.slavikjunior.kache.android

import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.core.StorageEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [clearWhenScopeCancelled] ties the cache to the lifetime of the scope the caller owns.
 *
 * The behaviour is easy to get subtly wrong: clearing has to run *after* the scope is
 * already cancelled, so a naive implementation never starts.
 */
class CacheLifecycleTest {

    private fun cacheOver(engine: StorageEngine) =
        L2KmpCache<String, String>(storageEngine = engine, valueSerializer = TextSerializer)

    private fun scopeWithJob(): Pair<CoroutineScope, CompletableDeferred<Unit>> {
        val job = CompletableDeferred<Unit>()
        return CoroutineScope(Dispatchers.Default + job) to job
    }

    @Test
    fun theCacheIsClearedWhenTheScopeCompletes() = runBlocking {
        val engine = RecordingStorageEngine()
        val cache = cacheOver(engine)
        cache.put("k", "v")

        val (scope, job) = scopeWithJob()
        val clearing = cache.clearWhenScopeCancelled(scope)

        job.complete(Unit)
        withTimeout(TIMEOUT_MS) { clearing.join() }

        assertTrue(engine.clearCount >= 1, "the cache must be cleared")
        assertEquals(0L, engine.size())
    }

    @Test
    fun theCacheIsClearedWhenTheScopeIsCancelled() = runBlocking {
        val engine = RecordingStorageEngine()
        val cache = cacheOver(engine)
        cache.put("k", "v")

        val (scope, job) = scopeWithJob()
        val clearing = cache.clearWhenScopeCancelled(scope)

        job.cancel()
        withTimeout(TIMEOUT_MS) { clearing.join() }

        assertTrue(engine.clearCount >= 1)
    }

    @Test
    fun nothingIsClearedWhileTheScopeIsAlive() = runBlocking {
        val engine = RecordingStorageEngine()
        val cache = cacheOver(engine)
        cache.put("k", "v")

        val (scope, job) = scopeWithJob()
        cache.clearWhenScopeCancelled(scope)

        // Give any stray work a chance to run; it must not clear.
        kotlinx.coroutines.delay(SETTLE_MS)

        assertEquals(0, engine.clearCount, "a live scope must not evict anything")
        assertEquals(1L, engine.size())

        job.complete(Unit)
        Unit
    }

    @Test
    fun theReturnedJobCompletesOnlyAfterTheClearing() = runBlocking {
        val engine = RecordingStorageEngine()
        val cache = cacheOver(engine)
        cache.put("k", "v")

        val (scope, job) = scopeWithJob()
        val clearing = cache.clearWhenScopeCancelled(scope)

        job.complete(Unit)
        withTimeout(TIMEOUT_MS) { clearing.join() }

        // Awaiting the job is what makes this usable in a test: if the extension returned
        // before the clear had happened, this assertion would race and fail.
        assertTrue(engine.clearCount >= 1, "the job must not complete before the clear")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 150L
    }
}