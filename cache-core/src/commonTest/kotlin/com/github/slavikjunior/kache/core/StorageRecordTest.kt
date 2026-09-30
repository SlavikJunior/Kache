package com.github.slavikjunior.kache.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageRecordTest {

    private val serializer = StringTestSerializer()

    @Test
    fun `a record with no TTL never expires`() {
        val record = StorageRecord(value = "value1", createdAt = 1_000L)

        assertNull(record.ttlMillis)
        assertFalse(record.isExpired(Long.MAX_VALUE))
        assertNull(record.expiresAt())
    }

    @Test
    fun `a record expires once its TTL has passed`() {
        val record = StorageRecord(value = "value1", createdAt = 1_000L, ttlMillis = 500L)

        assertFalse(record.isExpired(1_500L))
        assertTrue(record.isExpired(1_501L))
        assertEquals(1_500L, record.expiresAt())
    }

    @Test
    fun `create serializes the value and keeps the TTL`() {
        val record = StorageRecord.create("hello", serializer, createdAt = 1_000L, ttlMillis = 500L)

        assertEquals("hello", serializer.deserialize(record.data))
        assertEquals(500L, record.ttlMillis)
        assertEquals(1_000L, record.createdAt)
    }

    @Test
    fun `create surfaces serialization failures as typed exceptions`() {
        assertFailsWith<KacheException.SerializationException> {
            StorageRecord.create("hello", FailingSerializer(), createdAt = 0L)
        }
    }

    @Test
    fun `fromData restores a persisted payload`() {
        val original = StorageRecord.create("hello", serializer, createdAt = 1_000L, ttlMillis = 500L)

        val restored = StorageRecord.fromData(
            value = serializer.deserialize(original.data),
            data = original.data,
            createdAt = original.createdAt,
            ttlMillis = original.ttlMillis,
        )

        assertEquals(original.value, restored.value)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.expiresAt(), restored.expiresAt())
    }
}

class RetryPolicyTest {

    @Test
    fun `None never retries`() {
        assertFalse(RetryPolicy.None.shouldRetry(0))
        assertFalse(RetryPolicy.None.shouldRetry(10))
        assertEquals(0, RetryPolicy.None.delayAfter(1))
    }

    @Test
    fun `exponential retries up to the configured limit`() {
        val policy = RetryPolicy.Exponential(maxAttempts = 3)

        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(3))
        assertFalse(policy.shouldRetry(4))
        assertEquals(4, policy.totalAttempts)
    }

    @Test
    fun `exponential grows the delay between attempts`() {
        val policy = RetryPolicy.Exponential(
            maxAttempts = 4,
            initialDelayMs = 100,
            multiplier = 2.0,
            jitterRatio = 0.0,
        )

        assertEquals(100, policy.delayAfter(1))
        assertEquals(200, policy.delayAfter(2))
        assertEquals(400, policy.delayAfter(3))
        assertEquals(800, policy.delayAfter(4))
    }

    @Test
    fun `exponential caps the delay at the maximum`() {
        val policy = RetryPolicy.Exponential(
            maxAttempts = 5,
            initialDelayMs = 100,
            maxDelayMs = 250,
            multiplier = 10.0,
            jitterRatio = 0.0,
        )

        assertEquals(250, policy.delayAfter(3))
    }

    @Test
    fun `jitter only shortens the delay`() {
        val policy = RetryPolicy.Exponential(
            initialDelayMs = 1_000,
            jitterRatio = 0.5,
            jitterSource = { 0.0 },
        )

        assertEquals(500, policy.delayAfter(1))
    }

    @Test
    fun `zero attempts means the initial attempt only`() {
        val policy = RetryPolicy.Exponential(maxAttempts = 0)

        assertFalse(policy.shouldRetry(1))
        assertEquals(1, policy.totalAttempts)
    }

    @Test
    fun `fixed keeps a constant delay`() {
        val policy = RetryPolicy.fixed(attempts = 2, delayMs = 50)

        assertEquals(50, policy.delayAfter(1))
        assertEquals(50, policy.delayAfter(2))
        assertTrue(policy.shouldRetry(2))
        assertFalse(policy.shouldRetry(3))
    }

    @Test
    fun `delay cannot be requested before the first attempt`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy.exponential().delayAfter(0) }
    }

    @Test
    fun `invalid arguments are rejected`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy.Exponential(maxAttempts = -1) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy.Exponential(initialDelayMs = 0) }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy.Exponential(initialDelayMs = 500, maxDelayMs = 100)
        }
        assertFailsWith<IllegalArgumentException> { RetryPolicy.Exponential(multiplier = 0.5) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy.Exponential(jitterRatio = 1.5) }
    }

    @Test
    fun `factory presets keep their documented shape`() {
        assertEquals(3, RetryPolicy.exponential().maxAttempts)
        assertEquals(100, RetryPolicy.exponential().initialDelayMs)
        assertEquals(5, RetryPolicy.aggressive().maxAttempts)
        assertEquals(250, RetryPolicy.aggressive().initialDelayMs)
    }
}