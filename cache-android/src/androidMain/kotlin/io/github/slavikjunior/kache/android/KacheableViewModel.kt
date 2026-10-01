package io.github.slavikjunior.kache.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.CachedState
import io.github.slavikjunior.kache.core.KacheStateHolder
import io.github.slavikjunior.kache.core.KmpCache
import kotlinx.coroutines.flow.StateFlow

/**
 * A ViewModel that reads through a [KmpCache] and publishes the outcome as [kacheState].
 *
 * It exists to remove the plumbing every caching screen otherwise repeats: a
 * `StateFlow`, a job per read, and the mapping from cache events onto view state. The
 * state handling itself is not here — it lives in [KacheStateHolder], which is
 * cross-platform and carries no Android types. This class only supplies
 * [viewModelScope] to it, which is why there is no logic worth testing in isolation.
 *
 * Subclass it and call [load] once the screen's inputs are known:
 *
 * ```
 * class ProfileViewModel(app: Application) :
 *     KacheableViewModel<String, Profile>(app, cache = l2Cache(app, serializer)) {
 *
 *     init {
 *         load(key = "profile-42", fetcher = { repository.load("profile-42") })
 *     }
 * }
 * ```
 *
 * Nothing here owns the cache's lifetime. A cache that must outlive a screen belongs in an
 * application-scoped holder, not in a ViewModel, because the ViewModel is destroyed on a
 * configuration change. When a cache genuinely should die with the screen, reach for
 * [clearWhenScopeCancelled] and pass `viewModelScope`.
 *
 * @param K Key type accepted by [cache]. Pass `String` for anything created by [l2Cache].
 * @param V Value type held by [cache].
 */
public abstract class KacheableViewModel<K, V>(
    application: Application,
    cache: KmpCache<K, V>,
) : AndroidViewModel(application) {

    private val holder = KacheStateHolder<K, V>(scope = viewModelScope, cache = cache)

    /**
     * Current state of the tracked read: data, where it came from, and whether a fetch is
     * in flight.
     *
     * Prefixed rather than named `state` so that a subclass is free to use that name for
     * whatever else the screen tracks.
     */
    public val kacheState: StateFlow<CachedState<V>> get() = holder.kacheState

    /**
     * Reads [key] through the cache, fetching fresh data when [strategy] requires it.
     *
     * Open rather than abstract: the default already does the right thing, and forcing
     * every subclass to override it would add a pointless override that only forwards.
     * Override to add screen-specific behaviour such as logging or an analytics event.
     *
     * Calling this again cancels the previous read, so the state never mixes two keys.
     */
    public open fun load(
        key: K,
        fetcher: suspend (K) -> V,
        strategy: CacheStrategy = CacheStrategy.CacheFirst,
    ): Unit = holder.load(key, fetcher, strategy)

    /** Repeats the most recent [load]. Does nothing when [load] was never called. */
    public fun retry(): Unit = holder.retry()

    /** Drops [key] from the cache, so a later read reports a miss. */
    public suspend fun invalidate(key: K): Unit = holder.invalidate(key)

    /** Empties the cache and resets [kacheState]. */
    public suspend fun clearCache(): Unit = holder.clearCache()
}