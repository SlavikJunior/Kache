package io.github.slavikjunior.kache.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the projection from cache events onto [CachedState].
 *
 * The behaviour worth protecting is that a state holder never loses the value it is
 * already showing, because that is what a `StateFlow` of raw events would do.
 */
class KacheStateHolderTest {

    private val time = MutableTimeSource(initialTimeMillis = 0L)
    private val serializer = StringSerializer()
    private val engine = InMemoryStorageEngine(serializer)

    private fun holder(scope: CoroutineScope) = KacheStateHolder<String, String>(
        scope = scope,
        cache = L2KmpCache(
            storageEngine = engine,
            valueSerializer = serializer,
            defaultTtl = TTL,
            timeSource = time,
        ),
    )

    @Test
    fun initialStateIsEmptyAndIdle() = runTest {
        val holder = holder(this)

        val state = holder.kacheState.value

        assertNull(state.data)
        assertNull(state.origin)
        assertNull(state.error)
        assertFalse(state.isLoading)
        assertFalse(state.hasData)
    }

    @Test
    fun aSuccessfulLoadPublishesDataAndItsOrigin() = runTest {
        val holder = holder(this)

        holder.load(key = "k", fetcher = { "fresh" })
        advance()

        val state = holder.kacheState.value
        assertEquals("fresh", state.data)
        assertEquals(CacheOrigin.NETWORK, state.origin)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertTrue(state.hasData)
    }

    @Test
    fun aFailureWithNoCachedDataPublishesTheError() = runTest {
        val holder = holder(this)

        holder.load(key = "k", fetcher = { throw TestNetworkException("offline") })
        advance()

        val state = holder.kacheState.value
        assertNull(state.data)
        assertIs<KacheException.NetworkException>(state.error)
        assertFalse(state.isLoading)
    }

    @Test
    fun theDisplayedValueSurvivesAFailedRefresh() = runTest {
        val holder = holder(this)
        holder.load(key = "k", fetcher = { "good" })
        advance()
        assertEquals("good", holder.kacheState.value.data)

        // NetworkFirst falls back to the cached value, and the holder must not blank the
        // screen on the way.
        holder.load(key = "k", fetcher = { throw TestNetworkException("offline") },
            strategy = CacheStrategy.NetworkFirst)
        advance()

        assertEquals("good", holder.kacheState.value.data)
        assertEquals(CacheOrigin.DISK, holder.kacheState.value.origin)
    }

    @Test
    fun cacheAndNetworkKeepsTheOldValueVisibleWhileRefreshing() = runTest {
        val holder = holder(this)
        holder.load(key = "k", fetcher = { "old" })
        advance()

        holder.load(key = "k", fetcher = { "new" }, strategy = CacheStrategy.CacheAndNetwork)
        advance()

        // Final state is the fresh value.
        assertEquals("new", holder.kacheState.value.data)
        assertEquals(CacheOrigin.NETWORK, holder.kacheState.value.origin)
    }

    @Test
    fun retryRepeatsTheLastRequest() = runTest {
        val holder = holder(this)
        var attempts = 0
        holder.load(key = "k", fetcher = {
            attempts++
            throw TestNetworkException("down")
        })
        advance()
        assertEquals(1, attempts)

        // The policy is None by default, so retry re-runs the same failing fetch once.
        holder.retry()
        advance()

        assertEquals(2, attempts, "retry must repeat the request")
    }

    @Test
    fun retryDoesNothingBeforeAnyLoad() = runTest {
        val holder = holder(this)

        holder.retry()
        advance()

        assertNull(holder.kacheState.value.data)
        assertNull(holder.kacheState.value.error)
    }

    @Test
    fun aSecondLoadCancelsTheFirstSoTwoKeysNeverMix() = runTest {
        val holder = holder(this)

        holder.load(key = "a", fetcher = { "value-a" })
        holder.load(key = "b", fetcher = { "value-b" })
        advance()

        assertEquals("value-b", holder.kacheState.value.data)
    }

    @Test
    fun clearCacheEmptiesStorageAndResetsTheState() = runTest {
        val holder = holder(this)
        holder.load(key = "k", fetcher = { "v" })
        advance()

        holder.clearCache()
        advance()

        assertNull(holder.kacheState.value.data)
        assertNull(holder.kacheState.value.origin)
        assertEquals(0L, engine.size())
    }

    @Test
    fun invalidateMakesTheNextReadMiss() = runTest {
        val holder = holder(this)
        holder.load(key = "k", fetcher = { "v" })
        advance()

        holder.invalidate("k")
        advance()

        assertEquals(0L, engine.size())
    }

    @Test
    fun cachedStateKeepsTheFallbackAlongsideTheError() = runTest {
        val state = CachedState(
            data = "stale",
            origin = CacheOrigin.DISK_STALE,
            isLoading = false,
            error = KacheException.NetworkException(TestNetworkException("offline")),
        )

        // An error does not mean empty: this is the case a UI has to render as
        // "showing stale data with a warning".
        assertTrue(state.hasData)
        assertEquals("stale", state.data)
        assertEquals(CacheOrigin.DISK_STALE, state.origin)
    }

    /** Lets the holder's launched collection run to completion on the test scheduler. */
    private fun TestScope.advance() {
        testScheduler.advanceUntilIdle()
    }

    private companion object {
        val TTL = 1.seconds
    }
}