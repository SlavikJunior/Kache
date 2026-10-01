package io.github.slavikjunior.kache.core

/** The source from which cached data was obtained. */
public enum class CacheOrigin {
    /** Data was read from the in-memory cache. */
    MEMORY,

    /** Data was read from the in-memory cache but is stale (TTL expired). */
    MEMORY_STALE,

    /** Data was read from persistent storage. */
    DISK,

    /** Data was read from persistent storage but is stale (TTL expired). */
    DISK_STALE,

    /** Data was obtained from the network. */
    NETWORK,
}