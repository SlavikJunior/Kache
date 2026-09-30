package com.github.slavikjunior.kache.core

/** A reactive result emitted by a cache operation. */
public sealed interface CacheResult<out T> {
    /** A value was successfully obtained from [origin]. */
    public data class Success<out T>(
        public val data: T,
        public val origin: CacheOrigin,
    ) : CacheResult<T>

    /** An operation is in progress; [cachedData] may contain a stale value. */
    public data class Loading<out T>(
        public val cachedData: T? = null,
    ) : CacheResult<T>

    /** An operation failed; [cachedData] may contain a usable fallback value. */
    public data class Error<out T>(
        public val error: KacheException,
        public val cachedData: T? = null,
    ) : CacheResult<T>
}
