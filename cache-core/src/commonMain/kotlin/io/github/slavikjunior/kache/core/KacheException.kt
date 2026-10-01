package io.github.slavikjunior.kache.core

/** Base type for all failures reported by Kache. */
public sealed class KacheException(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** A network operation failed. */
    public class NetworkException(override val cause: Throwable) : KacheException(cause = cause)

    /** Reading from persistent storage failed. */
    public class DiskReadException(override val cause: Throwable) : KacheException(cause = cause)

    /** Writing to persistent storage failed. */
    public class DiskWriteException(override val cause: Throwable) : KacheException(cause = cause)

    /** Data serialization or deserialization failed. */
    public class SerializationException(override val cause: Throwable) : KacheException(cause = cause)

    /** The cached entry expired before it could be used. */
    public class ExpiredException(public val key: Any) : KacheException(
        message = "Cache entry expired for key=$key",
    ) {
        override fun toString(): String = "ExpiredException(key=$key)"
    }

    /** Cache miss - no data found in any tier and no fetcher provided. */
    public class CacheMissException(message: String) : KacheException(message = message)

    /** An unexpected failure that has no more specific Kache type. */
    public class UnknownKacheException(override val cause: Throwable) : KacheException(cause = cause)
}