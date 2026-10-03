package io.github.slavikjunior.kache.core

import kotlin.time.Duration

import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * Source of the current time, in milliseconds since the epoch.
 *
 * Every TTL decision in Kache goes through this abstraction instead of reading the
 * system clock directly, so that a test can place an entry exactly on its expiry
 * boundary rather than sleeping and hoping.
 *
 * Implementations must be safe to read from multiple threads.
 */
public fun interface TimeSource {
    /** The current time in milliseconds since the epoch. */
    public fun currentTimeMillis(): Long
}

/** [TimeSource] backed by the system clock. */
public object SystemTimeSource : TimeSource {
    override fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

/**
 * [TimeSource] whose time only changes when the caller says so.
 *
 * Intended for tests: advance the clock to make entries expire without waiting, and
 * keep the result reproducible.
 *
 * @param initialTimeMillis Starting time in milliseconds since the epoch.
 */
public class MutableTimeSource(initialTimeMillis: Long = 0L) : TimeSource {

    @Volatile
    private var currentTimeMillis: Long = initialTimeMillis

    override fun currentTimeMillis(): Long = currentTimeMillis

    /**
     * Moves the clock forward.
     *
     * @param amount How far to move the clock forward. Must not be negative, since the
     *   source cannot travel backwards.
     * @throws IllegalArgumentException if [amount] is negative.
     */
    public fun advance(amount: Duration) {
        require(amount >= Duration.ZERO) { "amount must be >= 0, but was $amount" }
        currentTimeMillis += amount.inWholeMilliseconds
    }

    /**
     * Sets the clock to an absolute value.
     *
     * @param timeMillis The new time in milliseconds since the epoch.
     */
    public fun setTo(timeMillis: Long) {
        currentTimeMillis = timeMillis
    }
}
