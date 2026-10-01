package io.github.slavikjunior.kache.android

import io.github.slavikjunior.kache.core.KmpCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clears this cache when [scope] is cancelled.
 *
 * Pass `viewModelScope` to tie the cache to a screen's lifetime: the returned job clears
 * the entries as soon as the ViewModel is torn down. That is the right behaviour for a
 * cache holding data that becomes meaningless once the screen is gone, and the wrong
 * behaviour for one that is meant to survive process death — the cache on disk is still
 * readable either way, but the in-memory tier is dropped either way too.
 *
 * Pass an application-scoped scope instead when the cache must outlive every screen.
 *
 * The returned [Job] completes once the clearing has finished, which makes it awaitable
 * in tests.
 */
public fun <K, V> KmpCache<K, V>.clearWhenScopeCancelled(scope: CoroutineScope): Job =
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            // Never completes on its own: this coroutine exists to observe cancellation.
            awaitCancellation()
        } finally {
            // The scope is already cancelled, so clearing has to opt out of the cancelled
            // context or it would never start.
            withContext(NonCancellable) { clear() }
        }
    }