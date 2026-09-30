package com.github.slavikjunior.kache.core

import kotlin.math.pow

/**
 * Strategy controlling how failures of a network fetch are retried.
 *
 * Implemented as a policy rather than a hardcoded loop so that a caller can opt into
 * a single attempt, tune backoff, or extend the sealed hierarchy later without
 * breaking existing call sites.
 */
public sealed interface RetryPolicy {

    /**
     * Whether another attempt is permitted after [completedAttempts] attempts.
     *
     * @param completedAttempts Number of attempts already made, at least 0.
     * @return true when the failed attempt should be followed by another one.
     */
    public fun shouldRetry(completedAttempts: Int): Boolean

    /**
     * Delay before the retry that follows [completedAttempts] completed attempts.
     *
     * @param completedAttempts Number of attempts already made, at least 1.
     * @return Delay in milliseconds. Never negative; 0 means "retry immediately".
     */
    public fun delayAfter(completedAttempts: Int): Long

    /** No retries. The initial failure is reported immediately. */
    public data object None : RetryPolicy {
        override fun shouldRetry(completedAttempts: Int): Boolean = false

        override fun delayAfter(completedAttempts: Int): Long = 0L
    }

    /**
     * Retries with exponential backoff and optional jitter.
     *
     * The delay before attempt number `n` (1-indexed) is
     * `initialDelayMs * multiplier^(n-1)`, capped at [maxDelayMs]. Jitter, when enabled,
     * scales the delay by a random factor in `[1 - jitterRatio, 1]` to keep many clients
     * from retrying in lockstep after a shared outage.
     *
     * @param maxAttempts Number of retries after the initial attempt. Zero means the
     *   initial attempt only. Must not be negative.
     * @param initialDelayMs Delay before the first retry, in milliseconds. Must be positive.
     * @param maxDelayMs Upper bound on any single delay, in milliseconds.
     * @param multiplier Growth factor applied per attempt. Must be at least 1.
     * @param jitterRatio Fraction of the delay to randomize, in `[0, 1]`. Zero disables jitter.
     * @param jitterSource Randomness source. Injectable so tests stay deterministic.
     *
     * @throws IllegalArgumentException if any argument is outside its documented range.
     */
    public class Exponential constructor(
        public val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        public val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
        public val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
        public val multiplier: Double = DEFAULT_MULTIPLIER,
        public val jitterRatio: Double = DEFAULT_JITTER_RATIO,
        private val jitterSource: () -> Double = { kotlin.random.Random.nextDouble() },
    ) : RetryPolicy {

        init {
            require(maxAttempts >= 0) { "maxAttempts must be >= 0, but was $maxAttempts" }
            require(initialDelayMs > 0) { "initialDelayMs must be > 0, but was $initialDelayMs" }
            require(maxDelayMs >= initialDelayMs) {
                "maxDelayMs ($maxDelayMs) must be >= initialDelayMs ($initialDelayMs)"
            }
            require(multiplier >= 1.0) { "multiplier must be >= 1.0, but was $multiplier" }
            require(jitterRatio in 0.0..1.0) { "jitterRatio must be in 0..1, but was $jitterRatio" }
        }

        /** Total number of attempts, including the initial one. */
        public val totalAttempts: Int get() = maxAttempts + 1

        override fun shouldRetry(completedAttempts: Int): Boolean = completedAttempts <= maxAttempts

        override fun delayAfter(completedAttempts: Int): Long {
            require(completedAttempts >= 1) {
                "completedAttempts must be >= 1, but was $completedAttempts"
            }
            val backoff = initialDelayMs.toDouble() * multiplier.pow(completedAttempts - 1)
            val capped = backoff.coerceAtMost(maxDelayMs.toDouble())
            if (jitterRatio == 0.0) return capped.toLong()
            val factor = 1.0 - jitterRatio + (jitterRatio * jitterSource()).coerceIn(0.0, 1.0)
            return (capped * factor).toLong().coerceAtLeast(0L)
        }
    }

    public companion object {
        private const val DEFAULT_MAX_ATTEMPTS: Int = 3
        private const val DEFAULT_INITIAL_DELAY_MS: Long = 100L
        private const val DEFAULT_MAX_DELAY_MS: Long = 5_000L
        private const val DEFAULT_MULTIPLIER: Double = 2.0
        private const val DEFAULT_JITTER_RATIO: Double = 0.2

        /** Default exponential backoff: 3 retries starting at 100 ms. */
        public fun exponential(): Exponential = Exponential()

        /** Exponential backoff tuned for aggressive recovery: 5 retries starting at 250 ms. */
        public fun aggressive(): Exponential = Exponential(
            maxAttempts = 5,
            initialDelayMs = 250L,
        )

        /** Fixed-interval backoff: [attempts] retries, every [delayMs]. */
        public fun fixed(attempts: Int = DEFAULT_MAX_ATTEMPTS, delayMs: Long = DEFAULT_INITIAL_DELAY_MS): Exponential =
            Exponential(
                maxAttempts = attempts,
                initialDelayMs = delayMs,
                maxDelayMs = delayMs,
                multiplier = 1.0,
                jitterRatio = 0.0,
            )
    }
}
