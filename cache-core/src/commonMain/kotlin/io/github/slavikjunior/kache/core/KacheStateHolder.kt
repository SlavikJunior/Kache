package io.github.slavikjunior.kache.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives a [KmpCache] and exposes the outcome as a [CachedState] flow.
 *
 * This is the cross-platform half of the view layer: it holds no Android types, so it
 * works from `commonMain`, from a repository, from a presenter, and from Swift. The
 * Android convenience wrapper `KacheableViewModel` is a thin subclass that only supplies
 * `viewModelScope` to this class.
 *
 * The [scope] is supplied by the caller rather than created here, because the lifetime of
 * the owner decides when in-flight reads stop. A ViewModel passes `viewModelScope`; an
 * application-scoped cache should pass its own scope instead.
 *
 * @param K Key type accepted by [cache].
 * @param V Value type held by [cache].
 * @property scope Scope in which reads are launched. Cancelling it stops collection.
 * @property cache The cache to read from and write to.
 */
public class KacheStateHolder<K, V>(
    private val scope: CoroutineScope,
    private val cache: KmpCache<K, V>,
) {

    private val _kacheState = MutableStateFlow(CachedState<V>())

    /**
     * Current state of the tracked read.
     *
     * The name is deliberately prefixed: `state` is the most contested identifier in an
     * Android view layer, and a library should not make consumers rename their own.
     */
    public val kacheState: StateFlow<CachedState<V>> = _kacheState.asStateFlow()

    /** Kept so [retry] can repeat the last request, and private so it cannot go stale. */
    private var lastRequest: Request<K, V>? = null

    private var loadJob: Job? = null

    /**
     * Reads [key], fetching fresh data when [strategy] asks for it, and publishes the
     * outcome into [kacheState].
     *
     * A call cancels the read started previously, so the state never reflects two keys at
     * once.
     *
     * @param key Key to read.
     * @param fetcher Loads fresh data. Called only when the strategy needs it, so an
     *   expensive fetch is skipped for a satisfied cache.
     * @param strategy How cached and fetched data are balanced.
     */
    public fun load(
        key: K,
        fetcher: suspend (K) -> V,
        strategy: CacheStrategy = CacheStrategy.CacheFirst,
    ) {
        val request = Request(key, fetcher, strategy)
        lastRequest = request

        loadJob?.cancel()
        loadJob = scope.launch {
            cache.get(request.key, request.strategy) { request.fetcher(it) }
                .collect { result ->
                    _kacheState.update { previous -> result.foldedInto(previous) }
                }
        }
    }

    /**
     * Repeats the most recent [load] request.
     *
     * Does nothing when [load] has never been called, because there is no request to
     * repeat and no key to fetch under.
     */
    public fun retry() {
        val request = lastRequest ?: return
        load(request.key, request.fetcher, request.strategy)
    }

    /** Drops [key] from the cache, so a later read reports a miss. */
    public suspend fun invalidate(key: K) {
        cache.invalidate(key)
    }

    /** Empties the cache and resets [kacheState] to its initial value. */
    public suspend fun clearCache() {
        cache.clear()
        _kacheState.value = CachedState()
    }

    private fun CacheResult<V>.foldedInto(previous: CachedState<V>): CachedState<V> = when (this) {
        // Keep whatever is on screen: a refresh that has already produced stale data
        // should replace it, but a refresh that has not should not blank the view.
        is CacheResult.Loading -> previous.copy(
            data = cachedData ?: previous.data,
            isLoading = true,
            error = null,
        )

        is CacheResult.Success -> CachedState(
            data = data,
            origin = origin,
            isLoading = false,
            error = null,
        )

        // An error may still carry a usable fallback, so the value survives it.
        is CacheResult.Error -> previous.copy(
            data = this.cachedData ?: previous.data,
            isLoading = false,
            error = this.error,
        )
    }

    private class Request<K, V>(
        val key: K,
        val fetcher: suspend (K) -> V,
        val strategy: CacheStrategy,
    )
}