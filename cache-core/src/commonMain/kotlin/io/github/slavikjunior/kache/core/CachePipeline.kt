package io.github.slavikjunior.kache.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlinx.coroutines.flow.flow

/**
 * Read/write primitives for the storage tiers a [CachePipeline] drives.
 *
 * A tier is deliberately not aware of [CacheStrategy]: it only knows how to resolve a
 * value, where it came from and how stale it is. All policy lives in the pipeline, so a
 * one-tier cache and a two-tier cache behave identically.
 *
 * Implementations must be thread-safe.
 *
 * @param K Key type accepted by this tier.
 * @param V Value type held by this tier.
 */
internal interface CacheTier<K, V> {

    /**
     * Clock this tier judges record freshness by.
     *
     * A tier answers freshness questions about records it owns, so it must expose the
     * clock it uses. The pipeline reads the time from here rather than carrying its own,
     * which is what keeps a shared tier and its records on one clock.
     */
    val timeSource: TimeSource

    /**
     * Resolves the value stored under [key].
     *
     * @param key The key to read.
     * @param allowStale When false, an expired record is discarded and reported as a
     *   miss. When true, it is returned and kept.
     * @return The value with the tier it came from, or null on a miss.
     */
    suspend fun read(key: K, allowStale: Boolean): TieredValue<V>?

    /**
     * Stores [value] under [key], replacing any existing record.
     *
     * @param key The key to write.
     * @param value The value to store.
     * @param ttl How long the entry stays fresh, or null to never expire.
     */
    suspend fun write(key: K, value: V, ttl: Duration?)

    /**
     * Removes the record stored under [key].
     *
     * @param key The key to remove.
     */
    suspend fun remove(key: K)
}

/**
 * A value resolved from a tier, tagged with where it came from.
 *
 * @param value The resolved value.
 * @param origin The tier, and whether the value had already expired.
 */
internal class TieredValue<V>(
    val value: V,
    val origin: CacheOrigin,
)

/**
 * Turns a [CacheStrategy] plus a [CacheTier] into a stream of [CacheResult] states.
 *
 * This is the single place where strategy behaviour is defined, so a one-tier cache and
 * a two-tier cache cannot drift apart:
 *
 * - [CacheStrategy.CacheFirst] serves fresh cache data, otherwise fetches.
 * - [CacheStrategy.NetworkFirst] fetches first and falls back to cache.
 * - [CacheStrategy.CacheAndNetwork] emits cached data, then refreshes.
 * - [CacheStrategy.StaleWhileRevalidate] serves even expired data, then refreshes.
 *
 * @param K Key type.
 * @param V Value type.
 * @param tier Storage tiers backing this pipeline.
 * @param defaultTtl How long entries stay fresh when a write carries no TTL. Null means
 *   entries never expire.
 * @param retryPolicy How a failing fetcher is retried.
 */
internal class CachePipeline<K, V>(
    private val tier: CacheTier<K, V>,
    private val defaultTtl: Duration?,
    private val retryPolicy: RetryPolicy,
) {

    /**
     * Runs [strategy] for [key].
     *
     * @param key Key to read.
     * @param strategy How cached and network data are balanced.
     * @param fetcher Loads fresh data on a miss. Null means cache-only.
     * @return A flow of at most two states: the cached one, then the refresh outcome.
     */
    fun execute(
        key: K,
        strategy: CacheStrategy,
        fetcher: (suspend (K) -> V)?,
    ): Flow<CacheResult<V>> = when (strategy) {
        CacheStrategy.CacheFirst -> cacheFirst(key, fetcher)
        CacheStrategy.NetworkFirst -> networkFirst(key, fetcher)
        CacheStrategy.CacheAndNetwork -> cacheAndNetwork(key, fetcher)
        CacheStrategy.StaleWhileRevalidate -> staleWhileRevalidate(key, fetcher)
    }

    /**
     * Serves fresh cache data when available, otherwise fetches.
     *
     * An expired record is treated as a miss rather than served, so a client never
     * receives data it would have to discard.
     */
    private fun cacheFirst(key: K, fetcher: (suspend (K) -> V)?): Flow<CacheResult<V>> = flow {
        read(key, allowStale = false)?.let { cached ->
            emit(CacheResult.Success(cached.value, cached.origin))
            return@flow
        }

        when (val outcome = fetch(key, fetcher)) {
            is FetchOutcome.Success -> {
                write(key, outcome.value)
                emit(CacheResult.Success(outcome.value, CacheOrigin.NETWORK))
            }
            is FetchOutcome.Failure -> emit(CacheResult.Error(KacheException.NetworkException(outcome.cause)))
            FetchOutcome.Absent -> emit(CacheResult.Error(KacheException.CacheMissException(NO_FETCHER_MESSAGE)))
        }
    }

    /**
     * Fetches first and only reads cache when the fetch does not succeed.
     *
     * A network failure is not reported to the caller when cached data can take its
     * place; the cached value is emitted with its real origin instead.
     */
    private fun networkFirst(key: K, fetcher: (suspend (K) -> V)?): Flow<CacheResult<V>> = flow {
        when (val outcome = fetch(key, fetcher)) {
            is FetchOutcome.Success -> {
                write(key, outcome.value)
                emit(CacheResult.Success(outcome.value, CacheOrigin.NETWORK))
                return@flow
            }
            is FetchOutcome.Failure, FetchOutcome.Absent -> Unit
        }

        read(key, allowStale = false)?.let { cached ->
            emit(CacheResult.Success(cached.value, cached.origin))
            return@flow
        }

        emit(CacheResult.Error(KacheException.CacheMissException(NO_DATA_AFTER_FETCH_MESSAGE)))
    }

    /**
     * Emits cached data when present, then always emits the network result.
     *
     * The point of this strategy is to show something immediately while still getting
     * fresh data, so the fetch is never skipped and its result is always emitted. A
     * fetch failure is only surfaced when there is no cached value to fall back on.
     */
    private fun cacheAndNetwork(key: K, fetcher: (suspend (K) -> V)?): Flow<CacheResult<V>> = flow {
        val cached = read(key, allowStale = false)
        if (cached != null) {
            emit(CacheResult.Success(cached.value, cached.origin))
        }

        when (val outcome = fetch(key, fetcher)) {
            is FetchOutcome.Success -> {
                write(key, outcome.value)
                emit(CacheResult.Success(outcome.value, CacheOrigin.NETWORK))
            }
            is FetchOutcome.Failure -> if (cached == null) {
                emit(CacheResult.Error(KacheException.NetworkException(outcome.cause)))
            }
            FetchOutcome.Absent -> if (cached == null) {
                emit(CacheResult.Error(KacheException.CacheMissException(NO_FETCHER_MESSAGE)))
            }
        }
    }

    /**
     * Emits cached data even when it has already expired, then refreshes.
     *
     * Stale values carry a `_STALE` origin so consumers can tell that a refresh follows.
     * The stale record stays in the cache while the fetch runs: a successful fetch
     * replaces it, a failed one leaves it in place for the next attempt.
     */
    private fun staleWhileRevalidate(key: K, fetcher: (suspend (K) -> V)?): Flow<CacheResult<V>> = flow {
        val cached = read(key, allowStale = true)
        if (cached != null) {
            emit(CacheResult.Success(cached.value, cached.origin))
        }

        when (val outcome = fetch(key, fetcher)) {
            is FetchOutcome.Success -> {
                write(key, outcome.value)
                emit(CacheResult.Success(outcome.value, CacheOrigin.NETWORK))
            }
            is FetchOutcome.Failure -> if (cached == null) {
                emit(CacheResult.Error(KacheException.NetworkException(outcome.cause)))
            }
            FetchOutcome.Absent -> if (cached == null) {
                emit(CacheResult.Error(KacheException.CacheMissException(NO_FETCHER_MESSAGE)))
            }
        }
    }

    private suspend fun read(key: K, allowStale: Boolean): TieredValue<V>? = tier.read(key, allowStale)

    private suspend fun write(key: K, value: V) {
        tier.write(key, value, defaultTtl)
    }

    /**
     * Runs the fetcher, applying [retryPolicy], and distinguishes "no fetcher" from a
     * genuine failure so that callers can report them differently.
     *
     * Coroutine cancellation is rethrown rather than reported as a network failure, so
     * that cancelling a collection is never mistaken for a failed request.
     */
    private suspend fun fetch(key: K, fetcher: (suspend (K) -> V)?): FetchOutcome<V> {
        if (fetcher == null) return FetchOutcome.Absent

        var completedAttempts = 0
        while (true) {
            try {
                return FetchOutcome.Success(fetcher(key))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                completedAttempts++
                if (!retryPolicy.shouldRetry(completedAttempts)) return FetchOutcome.Failure(e)

                val retryDelay = retryPolicy.delayAfter(completedAttempts)
                if (retryDelay > Duration.ZERO) delay(retryDelay)
            }
        }
    }

    private companion object {
        private const val NO_FETCHER_MESSAGE: String = "No fetcher provided and cache miss"
        private const val NO_DATA_AFTER_FETCH_MESSAGE: String =
            "Network fetch failed and no cached data available"
    }
}

/** Outcome of invoking a fetcher, separating absence of a fetcher from a real failure. */
internal sealed interface FetchOutcome<out T> {
    class Success<T>(val value: T) : FetchOutcome<T>
    class Failure(val cause: Exception) : FetchOutcome<Nothing>
    data object Absent : FetchOutcome<Nothing>
}