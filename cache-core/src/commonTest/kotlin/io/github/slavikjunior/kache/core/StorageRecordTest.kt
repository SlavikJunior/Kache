package io.github.slavikjunior.kache.core

import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageRecordTest {

    private val serializer = StringSerializer()

    @Test
    fun expiresAtIsCreatedAtPlusTtl() {
        val record = StorageRecord.create("v", serializer, createdAt = 1_000L, ttl = 500.milliseconds)

        assertEquals(1_500L, record.expiresAt())
    }

    @Test
    fun recordWithoutTtlNeverExpires() {
        val record = StorageRecord.create("v", serializer, createdAt = 1_000L, ttl = null)

        assertNull(record.expiresAt())
        assertFalse(record.isExpired(currentTimeMillis = Long.MAX_VALUE))
    }

    @Test
    fun expiryIsStrictlyGreaterThanExpiryTimestamp() {
        val record = StorageRecord.create("v", serializer, createdAt = 1_000L, ttl = 500.milliseconds)

        // Exactly at the deadline the value is still usable; one millisecond later it is not.
        assertFalse(record.isExpired(currentTimeMillis = 1_500L))
        assertTrue(record.isExpired(currentTimeMillis = 1_501L))
    }

    @Test
    fun createSerializesTheValueIntoData() {
        val record = StorageRecord.create("hello", serializer, createdAt = 0L)

        assertEquals("hello", record.value)
        assertEquals("hello", serializer.deserialize(record.data))
    }

    @Test
    fun fromDataKeepsTheDecodedValueAndRawPayload() {
        val payload = "raw".encodeToByteArray()
        val record = StorageRecord.fromData("decoded", payload, createdAt = 42L, ttl = 7.milliseconds)

        assertEquals("decoded", record.value)
        assertContentEquals(payload, record.data)
        assertEquals(42L, record.createdAt)
        assertEquals(7.milliseconds, record.ttl)
    }

    @Test
    fun serializationFailureIsMappedToTheKacheHierarchy() {
        val thrown = runCatching {
            StorageRecord.create("v", FailingSerializer(), createdAt = 0L)
        }.exceptionOrNull()

        assertTrue(thrown is KacheException.SerializationException, "was $thrown")
    }
}