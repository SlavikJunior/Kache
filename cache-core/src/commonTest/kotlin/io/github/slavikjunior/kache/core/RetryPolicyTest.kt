package io.github.slavikjunior.kache.core

import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryPolicyTest {

    @Test
    fun noneNeverRetries() {
        assertFalse(RetryPolicy.None.shouldRetry(completedAttempts = 1))
        assertFalse(RetryPolicy.None.shouldRetry(completedAttempts = 99))
        assertEquals(Duration.ZERO, RetryPolicy.None.delayAfter(1))
    }

    @Test
    fun fixedRetriesTheRequestedNumberOfTimes() {
        val policy = RetryPolicy.fixed(attempts = 2, delay = 50.milliseconds)

        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(2))
        assertFalse(policy.shouldRetry(3))
        assertEquals(3, policy.totalAttempts, "initial attempt plus two retries")
    }

    @Test
    fun fixedUsesTheSameDelayForEveryAttempt() {
        val policy = RetryPolicy.fixed(attempts = 3, delay = 50.milliseconds)

        assertEquals(50.milliseconds, policy.delayAfter(1))
        assertEquals(50.milliseconds, policy.delayAfter(2))
        assertEquals(50.milliseconds, policy.delayAfter(3))
    }

    @Test
    fun exponentialGrowsTheDelay() {
        val policy = RetryPolicy.Exponential(
            maxAttempts = 4,
            initialDelay = 100.milliseconds,
            maxDelay = 10.seconds,
            multiplier = 2.0,
            jitterRatio = 0.0,
        )

        assertEquals(100.milliseconds, policy.delayAfter(1))
        assertEquals(200.milliseconds, policy.delayAfter(2))
        assertEquals(400.milliseconds, policy.delayAfter(3))
        assertEquals(800.milliseconds, policy.delayAfter(4))
    }

    @Test
    fun exponentialClampsAtMaxDelay() {
        val policy = RetryPolicy.Exponential(
            maxAttempts = 10,
            initialDelay = 100.milliseconds,
            maxDelay = 500.milliseconds,
            multiplier = 10.0,
            jitterRatio = 0.0,
        )

        assertEquals(500.milliseconds, policy.delayAfter(5), "must never exceed maxDelay")
    }

    @Test
    fun jitterStaysWithinTheDocumentedBand() {
        val policy = RetryPolicy.Exponential(
            maxAttempts = 3,
            initialDelay = 1.seconds,
            maxDelay = 10.seconds,
            multiplier = 2.0,
            jitterRatio = 0.2,
            jitterSource = { 0.0 },
        )

        // The band is [1 - jitterRatio, 1], so the smallest possible delay is 800.
        assertEquals(800.milliseconds, policy.delayAfter(1))
    }

    @Test
    fun jitterUsesTheSuppliedSource() {
        val policy = RetryPolicy.Exponential(
            maxAttempts = 1,
            initialDelay = 1.seconds,
            maxDelay = 10.seconds,
            multiplier = 1.0,
            jitterRatio = 0.5,
            jitterSource = { 1.0 },
        )

        assertEquals(1.seconds, policy.delayAfter(1), "jitterSource = 1.0 means no reduction")
    }

    @Test
    fun aggressiveStartsAtFiveAttemptsAnd250ms() {
        val policy = RetryPolicy.aggressive()

        assertEquals(5, policy.maxAttempts)
        assertEquals(250.milliseconds, policy.initialDelay)
        assertTrue(policy.shouldRetry(5))
        assertFalse(policy.shouldRetry(6))
    }

    @Test
    fun zeroMaxAttemptsMeansASingleAttempt() {
        val policy = RetryPolicy.Exponential(maxAttempts = 0, initialDelay = 10.milliseconds)

        assertFalse(policy.shouldRetry(1))
        assertEquals(1, policy.totalAttempts)
    }

    @Test
    fun invalidArgumentsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy.Exponential(maxAttempts = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy.Exponential(initialDelay = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy.Exponential(initialDelay = 500.milliseconds, maxDelay = 100.milliseconds)
        }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy.Exponential(multiplier = 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy.Exponential(jitterRatio = 1.5)
        }
    }

    @Test
    fun exponentialRejectsANonPositiveAttemptCount() {
        val policy = RetryPolicy.Exponential(maxAttempts = 3, initialDelay = 10.milliseconds)

        assertFailsWith<IllegalArgumentException> { policy.delayAfter(0) }
        assertFailsWith<IllegalArgumentException> { policy.delayAfter(-1) }
    }

    @Test
    fun noneIgnoresTheAttemptCountEntirely() {
        // None never schedules a retry, so it has nothing to compute a delay from and
        // does not validate the argument. Exponential does, because it uses it.
        assertEquals(Duration.ZERO, RetryPolicy.None.delayAfter(0))
        assertEquals(Duration.ZERO, RetryPolicy.None.delayAfter(-5))
    }
}