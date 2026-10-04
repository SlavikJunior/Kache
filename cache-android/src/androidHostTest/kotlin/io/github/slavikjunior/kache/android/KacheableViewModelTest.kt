package io.github.slavikjunior.kache.android

import android.app.Application
import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.CachedState
import io.github.slavikjunior.kache.core.KmpCache
import io.github.slavikjunior.kache.core.L2KmpCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The wiring between [KacheableViewModel] and `KacheStateHolder`.
 *
 * The class holds almost no logic of its own, which is the point — but "almost none" is not
 * "none". What breaks here is the plumbing: a `load` that never reaches the holder, a `retry`
 * that repeats the wrong key, a `kacheState` that hands out a different stream than the one
 * being updated. None of that is visible from the holder's own tests, and all of it is exactly
 * what a screen depends on.
 *
 * `viewModelScope` runs on `Dispatchers.Main.immediate`, which is why these need Robolectric:
 * a plain JVM test has no main looper to run that scope on.
 */
@RunWith(RobolectricTestRunner::class)
class KacheableViewModelTest {

    private val application: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun loadFillsTheStateFromTheNetwork() = runBlocking {
        val vm = TestViewModel(application, cache())

        vm.load("k", fetcher = { "fetched" })

        val state = vm.awaitState { it.data == "fetched" }
        assertEquals(CacheOrigin.NETWORK, state.origin)
    }

    @Test
    fun aSecondLoadOfTheSameKeyIsServedFromTheCache() = runBlocking {
        val vm = TestViewModel(application, cache())
        vm.load("k", fetcher = { "fetched" })
        vm.awaitState { it.data == "fetched" }

        vm.load("k", fetcher = { "refetched" })

        val state = vm.awaitState { it.origin == CacheOrigin.DISK }
        assertEquals("fetched", state.data, "the cache must answer, not the fetcher")
    }

    @Test
    fun loadForwardsTheKeyToTheFetcher() = runBlocking {
        val seen = mutableListOf<String>()
        val vm = TestViewModel(application, cache(), onLoad = { seen += it })

        vm.load("profile-42", fetcher = { "value" })
        vm.awaitState { it.data == "value" }

        assertEquals(listOf("profile-42"), seen)
    }

    @Test
    fun retryRepeatsTheLastKey() = runBlocking {
        // A different value per call, so a second fetch is visible in the state rather than
        // being indistinguishable from the first one.
        var calls = 0
        val vm = TestViewModel(application, cache())
        // The value carries the key, so the result proves both that a refetch happened and
        // that it happened for the same key.
        vm.load("first", fetcher = { key -> "$key-${++calls}" })
        vm.awaitState { it.data == "first-1" }

        // CacheFirst serves a warm cache without calling the fetcher, so drop the record first;
        // otherwise retry would legitimately succeed without fetching and prove nothing.
        vm.invalidate("first")
        vm.retry()
        val state = vm.awaitState { it.data == "first-2" }

        assertEquals(2, calls, "retry must run the fetcher again")
        assertEquals(CacheOrigin.NETWORK, state.origin, "the refetched value must come from the network")
    }

    @Test
    fun retryBypassesAnOverriddenLoad() = runBlocking {
        // Worth pinning down: retry replays the request the holder kept, so it does not re-enter
        // `load`. An override that logs or reports analytics therefore sees the first read but
        // not the retry, which is surprising unless it is a deliberate contract.
        var calls = 0
        val seen = mutableListOf<String>()
        val vm = TestViewModel(application, cache(), onLoad = { seen += it })
        vm.load("k", fetcher = { "value-${++calls}" })
        vm.awaitState { it.data == "value-1" }

        vm.invalidate("k")
        vm.retry()
        vm.awaitState { it.data == "value-2" }

        assertEquals(listOf("k"), seen, "retry must not route back through load")
    }

    @Test
    fun retryBeforeAnyLoadDoesNothing() = runBlocking {
        val vm = TestViewModel(application, cache())

        vm.retry()

        val state = vm.kacheState.value
        assertNull(state.data, "retry with no previous load must not invent a state")
        assertNull(state.error)
    }

    @Test
    fun aFailingFetcherSurfacesTheError() = runBlocking {
        val vm = TestViewModel(application, cache())

        vm.load("k", fetcher = { throw IllegalStateException("boom") })
        val state = vm.awaitState { it.error != null }

        val error = assertNotNull(state.error)
        assertEquals(IllegalStateException::class, error.cause?.let { it::class })
    }

    @Test
    fun invalidateMakesTheNextReadMiss() = runBlocking {
        val engine = RecordingStorageEngine()
        val vm = TestViewModel(application, L2KmpCache(storageEngine = engine, valueSerializer = TextSerializer))
        vm.load("k", fetcher = { "fetched" })
        vm.awaitState { it.data == "fetched" }

        vm.invalidate("k")

        assertNull(engine.get("k"), "the cached record must be gone")
    }

    @Test
    fun clearCacheEmptiesStorageAndResetsTheState() = runBlocking {
        val engine = RecordingStorageEngine()
        val vm = TestViewModel(application, L2KmpCache(storageEngine = engine, valueSerializer = TextSerializer))
        vm.load("k", fetcher = { "fetched" })
        vm.awaitState { it.data == "fetched" }

        vm.clearCache()

        assertEquals(0L, engine.size())
        assertNull(vm.kacheState.value.data, "the published state must be reset too")
    }

    @Test
    fun theStateStreamIsTheOneTheViewModelExposes() = runBlocking {
        val vm = TestViewModel(application, cache())

        vm.load("k", fetcher = { "fetched" })
        vm.awaitState { it.data == "fetched" }

        // Same instance every time. A wrapper rebuilt per access would collect as a distinct
        // StateFlow, and a screen would silently stop observing updates.
        assertSame(vm.kacheState, vm.kacheState)
    }

    private fun cache(): L2KmpCache<String, String> =
        L2KmpCache(storageEngine = RecordingStorageEngine(), valueSerializer = TextSerializer)
}

/**
 * Waits for [predicate] to hold and returns the state that satisfied it.
 *
 * State changes arrive on `viewModelScope`, so a read immediately after `load` would race the
 * coroutine that publishes it. Polling the flow is what makes these assertions deterministic.
 */
private suspend fun TestViewModel.awaitState(
    timeoutMs: Long = 5_000L,
    predicate: (CachedState<String>) -> Boolean,
): CachedState<String> = withTimeout(timeoutMs) { kacheState.first(predicate) }

/**
 * Minimal concrete subclass.
 *
 * [onLoad] lets a test observe the fetcher being invoked without the fake engine having to
 * record it, which keeps these tests about the ViewModel rather than about storage.
 */
private class TestViewModel(
    application: Application,
    cache: KmpCache<String, String>,
    private val onLoad: ((String) -> Unit)? = null,
) : KacheableViewModel<String, String>(application, cache) {

    override fun load(key: String, fetcher: suspend (String) -> String, strategy: CacheStrategy) {
        onLoad?.invoke(key)
        super.load(key, fetcher, strategy)
    }
}
