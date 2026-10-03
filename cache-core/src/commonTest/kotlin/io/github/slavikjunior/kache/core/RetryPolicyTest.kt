package io.github.slavikjunior.kache.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryPolicyTest {

    @Test
    fun noneNeverRetries() {
        assertFalse(RetryPolicy.None.shouldRetry(completedAttempts = 1))
        assertFalse(RetryPolicy.None.shouldRetry(completedAttempts = 99))
        assertEquals(0L, RetryPolicy.None.delayAfter(1))
    }

    @Test
    fun fixedRetriesTheRequestedNumberOfTimes() {
        val policy = RetryPolicy.fixed(attempts = 2, delayMs = 50L)

        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(2))
        assertFalse(policy.shouldRetry(3))
        assertEquals(3, policy.totalAttempts, "initial attempt plus two retries")
    }

    @Test
    fun fixedUsesTheSameDelayForEveryAttempt() {
        val policy = RetryPolicy.fixed(attempts = 3, delayMs = 50L)

        assertEquals(50L, policy.delayAfter(1))
        assertEquals(50L, policy.delayAfter(2))
        assertEquals(50L, policy.delayAfter(3))
    }

    @Test
    fun exponentialGrowsTheDelay() {
        val policy = RetryPolicy.Exponential(
            maxAttempts = 4,
            initialDelayMs = 100L,
            maxDelayMs = 10_000L,
            multiplier = 2.0,
            jitterRatio = 0.0,
        )

        assertEquals(100L, policy.delayAfter(1))
        assertEquals(200L, policy.delayAfter(2))
        assertEquals(400L, policy.delayAfter(3))
        assertEquals(800L, policy.delayAfter(4))
    }

    @Test
    fun exponentialClampsAtMaxDelay() {
        val policy = RetryPolicy.Exponential(
            maxAttempts = 10,
            initialDelayMs = 100L,
            maxDelayMs = 500L,
            multiplier = 10.0,
            jitterRatio = 0.0,
        )

        assertEquals(500L, policy.delayAfter(5), "must never exceed maxDelayMs")
    }

    @Test
    fun jitterStaysWithinTheDocumentedBand() {
        val policy = RetryPolicy.Exponential(
            maxAttempts = 3,
            initialDelayMs = 1_000L,
            maxDelayMs = 10_000L,
            multiplier = 2.0,
            jitterRatio = 0.2,
            jitterSource = { 0.0 },
        )

        // The band is [1 - jitterRatio, 1], so the smallest possible delay is 800.
        assertEquals(800L, policy.delayAfter(1))
    }

    @Test
    fun jitterUsesTheSuppliedSource() {
        val policy = RetryPolicy.Exponential(
            maxAttempts = 1,
            initialDelayMs = 1_000L,
            maxDelayMs = 10_000L,
            multiplier = 1.0,
            jitterRatio = 0.5,
            jitterSource = { 1.0 },
        )

        assertEquals(1_000L, policy.delayAfter(1), "jitterSource = 1.0 means no reduction")
    }

    @Test
    fun aggressiveStartsAtFiveAttemptsAnd250ms() {
        val policy = RetryPolicy.aggressive()

        assertEquals(5, policy.maxAttempts)
        assertEquals(250L, policy.initialDelayMs)
        assertTrue(policy.shouldRetry(5))
        assertFalse(policy.shouldRetry(6))
    }

    @Test
    fun zeroMaxAttemptsMeansASingleAttempt() {
        val policy = RetryPolicy.Exponential(maxAttempts = 0, initialDelayMs = 10L)

        assertFalse(policy.shouldRetry(1))
        assertEquals(1, policy.totalAttempts)
    }

    @Test
    fun invalidArgumentsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy.Exponential(maxAttempts = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy.Exponential(initialDelayMs = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy.Exponential(initialDelayMs = 500L, maxDelayMs = 100L)
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
        val policy = RetryPolicy.Exponential(maxAttempts = 3, initialDelayMs = 10L)

        assertFailsWith<IllegalArgumentException> { policy.delayAfter(0) }
        assertFailsWith<IllegalArgumentException> { policy.delayAfter(-1) }
    }

    @Test
    fun noneIgnoresTheAttemptCountEntirely() {
        // None never schedules a retry, so it has nothing to compute a delay from and
        // does not validate the argument. Exponential does, because it uses it.
        assertEquals(0L, RetryPolicy.None.delayAfter(0))
        assertEquals(0L, RetryPolicy.None.delayAfter(-5))
    }
}