package com.github.slavikjunior.kache.core

/** Base type for all failures reported by Kache. */
public sealed class KacheException(cause: Throwable? = null) : Exception(cause) {
    /** A network operation failed. */
    public class NetworkException(override val cause: Throwable) : KacheException(cause)

    /** Reading from persistent storage failed. */
    public class DiskReadException(override val cause: Throwable) : KacheException(cause)

    /** Writing to persistent storage failed. */
    public class DiskWriteException(override val cause: Throwable) : KacheException(cause)

    /** Data serialization or deserialization failed. */
    public class SerializationException(override val cause: Throwable) : KacheException(cause)

    /** The cached entry expired before it could be used. */
    public class ExpiredException(public val key: Any) : KacheException() {
        override fun toString(): String = "ExpiredException(key=$key)"
    }

    /** Cache miss - no data found in any tier and no fetcher provided. */
    public class CacheMissException(message: String) : KacheException()

    /** An unexpected failure that has no more specific Kache type. */
    public class UnknownKacheException(override val cause: Throwable) : KacheException(cause)
}