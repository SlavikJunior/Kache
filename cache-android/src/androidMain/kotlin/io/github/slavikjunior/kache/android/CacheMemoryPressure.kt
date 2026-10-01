package io.github.slavikjunior.kache.android

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import io.github.slavikjunior.kache.core.KmpCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Registers [caches] for eviction when the system signals memory pressure.
 *
 * Only the levels at which the process is a genuine eviction candidate clear the cache.
 * An intermediate trim such as [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] means the
 * process is still alive and fully expected back: clearing there would turn a trim the
 * system considers survivable into a visible reload, and would discard data the user is
 * about to come back to.
 *
 * Call [unregisterCacheMemoryPressureCallbacks] when the caches go away, otherwise the
 * application retains them for the rest of the process lifetime.
 *
 * @param scope Scope used to clear. Use one that outlives the callback registration —
 *   cancelling it early would stop the clearing without saying so.
 * @param caches Caches to clear together on the terminal trim levels.
 * @return Handle to pass to [unregisterCacheMemoryPressureCallbacks].
 */
public fun Application.registerCacheMemoryPressureCallbacks(
    scope: CoroutineScope,
    vararg caches: KmpCache<*, *>,
): CacheMemoryPressureCallbacks {
    val callbacks = CacheMemoryPressureCallbacks(scope, caches.toList())
    registerComponentCallbacks(callbacks)
    return callbacks
}

/** Stops reacting to memory pressure and releases the registered caches. */
public fun Application.unregisterCacheMemoryPressureCallbacks(
    callbacks: CacheMemoryPressureCallbacks,
) {
    unregisterComponentCallbacks(callbacks)
}

/**
 * Whether a trim level is severe enough to justify dropping cached data.
 *
 * Returns true from [ComponentCallbacks2.TRIM_MEMORY_COMPLETE] upwards. Exposed for
 * tests, since the levels are only reachable through the Android runtime.
 */
internal fun requiresImmediateCacheClear(level: Int): Boolean = level >= TRIM_MEMORY_COMPLETE

private const val TRIM_MEMORY_COMPLETE = ComponentCallbacks2.TRIM_MEMORY_COMPLETE

/**
 * Reacts to memory pressure on behalf of a set of caches.
 *
 * Implements [ComponentCallbacks2] because that is what an [Application] dispatches; the
 * constant levels it delivers are the only signal available without a process-death
 * callback.
 */
public class CacheMemoryPressureCallbacks internal constructor(
    private val scope: CoroutineScope,
    private val caches: List<KmpCache<*, *>>,
) : ComponentCallbacks2 {

    override fun onTrimMemory(level: Int): Unit {
        if (requiresImmediateCacheClear(level)) clearAllCaches()
    }

    override fun onLowMemory(): Unit = clearAllCaches()

    /** A configuration change is not memory pressure, so the cache is left untouched. */
    override fun onConfigurationChanged(newConfig: Configuration): Unit = Unit

    private fun clearAllCaches() {
        scope.launch {
            caches.forEach { cache ->
                // One cache failing must not prevent the others from being cleared, which
                // would leave a partial eviction in place.
                runCatching { cache.clear() }
            }
        }
    }
}