package io.github.slavikjunior.kache.core

/**
 * A result emitted by a cache operation: what happened, never what is happening.
 *
 * A read of a revalidating strategy emits more than one of these — the cached value first,
 * the fetched one after it — so a caller that needs only the end state should take the last
 * emission, and a caller that renders the screen should fold them (or use [CachedState],
 * which does that folding).
 *
 * There is deliberately no `Loading` case. Progress is a state, not a result: the pipeline
 * publishes outcomes, and a revalidating read keeps the cached value on screen while the
 * network result is still outstanding — a fact a `StateFlow` of results cannot express
 * without dropping that value. [CachedState.isLoading] carries it instead, driven by
 * [KacheStateHolder], which knows when a read starts and when its flow completes. Emitting a
 * placeholder result would also change the emission count of [CacheStrategy.CacheFirst] from
 * one to two, so a caller taking `first()` would receive a placeholder where it previously
 * received data.
 */
public sealed interface CacheResult<out T> {
    /** A value was successfully obtained from [origin]. */
    public data class Success<out T>(
        public val data: T,
        public val origin: CacheOrigin,
    ) : CacheResult<T>

    /** An operation failed; [cachedData] may carry a usable fallback value. */
    public data class Error<out T>(
        public val error: KacheException,
        public val cachedData: T? = null,
    ) : CacheResult<T>
}
