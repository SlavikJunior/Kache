package io.github.slavikjunior.kache.core

/**
 * A flattened snapshot of a cache read, suitable for driving a view.
 *
 * [CacheResult] describes *events* and a flow of them, which is the right shape for a
 * pipeline but a poor fit for a state holder: a `StateFlow` keeps only its latest
 * emission, so mapping events straight onto it would drop the currently displayed value
 * the moment a refresh starts. This type keeps the last known data while
 * [isLoading] is true, so a refresh dims the screen instead of blanking it.
 *
 * It is deliberately framework-free. Compose, Views and Swift all render the same type,
 * so nothing here forces a UI toolkit on the consumer.
 *
 * @param V Value type held by the cache.
 * @property data Last known value, or null if nothing has been read yet. May still be
 *   populated while [isLoading] is true, which is the point of the type.
 * @property origin Where [data] came from, or null when no value is held.
 * @property isLoading A read or refresh is in flight.
 * @property error Failure of the last operation, or null. [data] may still hold a usable
 *   fallback, which is why the error does not imply empty state.
 */
public data class CachedState<out V>(
    val data: V? = null,
    val origin: CacheOrigin? = null,
    val isLoading: Boolean = false,
    val error: KacheException? = null,
) {
    /** True when a value is available, including a fallback carried alongside an error. */
    public val hasData: Boolean get() = data != null
}