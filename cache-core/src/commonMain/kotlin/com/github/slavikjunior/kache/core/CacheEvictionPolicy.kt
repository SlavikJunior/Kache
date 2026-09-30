package com.github.slavikjunior.kache.core

/**
 * Eviction policy for cache storage backends.
 */
public sealed interface CacheEvictionPolicy {
    /** Never evict records. Storage capacity is unlimited or managed externally. */
    object None : CacheEvictionPolicy

    /** Evict the Least Recently Used record when capacity is exceeded. */
    object LRU : CacheEvictionPolicy

    /** Evict the First In (oldest by timestamp) record when capacity is exceeded. */
    object FIFO : CacheEvictionPolicy

    /** Evict records based on size (estimated bytes). */
    object BySize : CacheEvictionPolicy

    companion object {
        /** Creates an LRU eviction policy with the given maximum capacity. */
        public fun lru(maxSize: Int) = LRU

        /** Creates an FIFO eviction policy with the given maximum capacity. */
        public fun fifo(maxSize: Int) = FIFO

        /** Creates a size-based eviction policy with the given maximum capacity in bytes. */
        public fun bySize(maxSizeBytes: Long) = BySize

        /** No eviction policy (unlimited storage). */
        public fun none() = None
    }
}
