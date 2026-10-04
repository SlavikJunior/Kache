package io.github.slavikjunior.kache.core

/**
 * Which record an over-capacity L2 tier drops first.
 *
 * Every strategy orders records by a timestamp the backend already stores, so all four
 * work identically on the file and database backends without extra bookkeeping:
 * [FIFO], [LIFO] order by [StorageRecord.createdAt], while [LRU] and [MRU] order by
 * [StorageRecord.lastAccessedAt].
 *
 * Note that [LRU] and [MRU] are only as accurate as [StorageEngine.touch] keeps them.
 * Reading a key updates its access timestamp at most once per touch window, so the
 * ordering is correct at window granularity rather than per individual read.
 *
 * Expired records are always removed ahead of whatever this strategy ranks first:
 * an expired record cannot be served by any read path, so dropping it loses nothing.
 */
public enum class EvictionStrategy {
    /** Least recently used: the record whose [StorageRecord.lastAccessedAt] is oldest. */
    LRU,

    /** First in, first out: the record whose [StorageRecord.createdAt] is oldest. */
    FIFO,

    /** Most recently used: the record whose [StorageRecord.lastAccessedAt] is newest. */
    MRU,

    /** Last in, first out: the record whose [StorageRecord.createdAt] is newest. */
    LIFO,
}

/**
 * Orders records so that the one to evict comes first.
 *
 * Shared by every backend so that the file and database implementations cannot drift
 * apart in what they consider the worst candidate. Records with equal timestamps come out
 * in whatever order the backend happened to enumerate them, which is unspecified — callers
 * that need a stable choice should keep timestamps distinct.
 *
 * @return A comparator placing the first eviction candidate at the head.
 */
public fun EvictionStrategy.recordComparator(): Comparator<StorageRecord<*>> = when (this) {
    EvictionStrategy.LRU -> compareBy { it.lastAccessedAt }
    EvictionStrategy.FIFO -> compareBy { it.createdAt }
    EvictionStrategy.MRU -> compareByDescending { it.lastAccessedAt }
    EvictionStrategy.LIFO -> compareByDescending { it.createdAt }
}
