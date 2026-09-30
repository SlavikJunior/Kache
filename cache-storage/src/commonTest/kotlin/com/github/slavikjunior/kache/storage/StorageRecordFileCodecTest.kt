package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.StorageRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StorageRecordFileCodecTest {

    private val serializer = StringSerializer()

    @Test
    fun `round-trips a record without a TTL`() {
        val record = StorageRecord.create(
            value = "value",
            serializer = serializer,
            createdAt = 1_700_000_000_000L,
        )

        val decoded = StorageRecordFileCodec.decode(StorageRecordFileCodec.encode(record))

        assertEquals(1_700_000_000_000L, decoded.createdAt)
        assertNull(decoded.ttlMillis)
        assertEquals("value", serializer.deserialize(decoded.data))
    }

    @Test
    fun `round-trips a record with a TTL`() {
        val record = StorageRecord.create(
            value = "value",
            serializer = serializer,
            createdAt = 1_000L,
            ttlMillis = 60_000L,
        )

        val decoded = StorageRecordFileCodec.decode(StorageRecordFileCodec.encode(record))

        assertEquals(60_000L, decoded.ttlMillis)
        assertEquals(1_000L + 60_000L, decoded.expiresAt())
    }

    @Test
    fun `round-trips a payload containing the field separator`() {
        // The payload must not be parsed as part of the header.
        val record = StorageRecord.create(
            value = "a|1|0|2|b",
            serializer = serializer,
            createdAt = 0L,
            ttlMillis = 1L,
        )

        val decoded = StorageRecordFileCodec.decode(StorageRecordFileCodec.encode(record))

        assertEquals("a|1|0|2|b", serializer.deserialize(decoded.data))
        assertEquals(1L, decoded.ttlMillis)
    }

    @Test
    fun `round-trips an empty payload`() {
        val record = StorageRecord(
            value = byteArrayOf(),
            createdAt = 0L,
        )

        val decoded = StorageRecordFileCodec.decode(StorageRecordFileCodec.encode(record))

        assertContentEquals(byteArrayOf(), decoded.data)
    }

    @Test
    fun `a truncated payload is rejected`() {
        val encoded = StorageRecordFileCodec.encode(
            StorageRecord.create("value", serializer, 0L)
        )

        assertFailsWith<IllegalArgumentException> {
            StorageRecordFileCodec.decode(encoded.copyOf(encoded.size - 2))
        }
    }

    @Test
    fun `a payload without separators is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StorageRecordFileCodec.decode("not-a-record".encodeToByteArray())
        }
    }

    @Test
    fun `a declared data length larger than the payload is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StorageRecordFileCodec.decode("0|0|999|x".encodeToByteArray())
        }
    }
}