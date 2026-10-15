package io.github.slavikjunior.kache.core

/**
 * Timestamp arithmetic that saturates at the ends of the [Long] range instead of wrapping.
 *
 * Both operands reach this code from outside the library: a `ttl` is whatever the caller
 * passed, and `createdAt` is read back out of a file or a database row, so a record can carry
 * a timestamp no clock would ever produce. Letting the arithmetic wrap turns that into a
 * record that expires the instant it is written, or one that never expires, decided by
 * overflow rather than by policy. Saturation keeps the record alive, which is the direction
 * a cache should fail in: a stale record is served and then rejected on TTL, an evicted
 * record is gone.
 */

/** This timestamp plus [delta], clamped to [Long.MIN_VALUE] or [Long.MAX_VALUE] on overflow. */
internal fun Long.saturatingAdd(delta: Long): Long = when {
    delta > 0L && this > Long.MAX_VALUE - delta -> Long.MAX_VALUE
    delta < 0L && this < Long.MIN_VALUE - delta -> Long.MIN_VALUE
    else -> this + delta
}

/** This timestamp minus [earlier], clamped to the ends of the [Long] range on overflow. */
internal fun Long.saturatingSubtract(earlier: Long): Long = when {
    earlier > 0L && this < Long.MIN_VALUE + earlier -> Long.MIN_VALUE
    earlier < 0L && this > Long.MAX_VALUE + earlier -> Long.MAX_VALUE
    else -> this - earlier
}