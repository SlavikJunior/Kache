package io.github.slavikjunior.kache.android

import io.github.slavikjunior.kache.core.KmpCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Clears this cache when [scope] finishes, whether it was cancelled or completed
 * normally.
 *
 * Pass `viewModelScope` to tie the cache to a screen's lifetime: the returned job evicts
 * the entries as soon as the ViewModel is torn down. That is the right behaviour for a
 * cache holding data that becomes meaningless once the screen is gone, and the wrong
 * behaviour for one meant to survive process death — the entries on disk stay readable
 * either way, but the in-memory tier is dropped either way too.
 *
 * Pass an application-scoped scope instead when the cache must outlive every screen.
 *
 * The clearing deliberately does *not* run in [scope]. That scope is finished by the time
 * this runs, so work launched there would either be cancelled immediately or, worse,
 * deadlock the caller: a coroutine that awaits cancellation keeps its parent from ever
 * completing, so awaiting the returned job would hang forever on a scope that completes
 * normally. A detached scope is used instead, and the returned [Job] completes once the
 * eviction has actually happened, which makes the call awaitable in tests.
 */
public fun <K, V> KmpCache<K, V>.clearWhenScopeCancelled(scope: CoroutineScope): Job {
    // Own scope, deliberately independent of `scope`: by the time the clearing runs the
    // caller has already finished.
    val clearingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val completion = Job()

    scope.coroutineContext.job.invokeOnCompletion {
        clearingScope.launch {
            try {
                clear()
            } finally {
                completion.complete()
            }
        }
    }

    return completion
}