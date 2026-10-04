package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.StorageRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The on-disk header, pinned field by field.
 *
 * [FileStorageEngineTest] proves records survive a round trip, which would still pass if the
 * header were reshuffled as long as both sides agreed. These tests fix the layout itself,
 * because the layout is a compatibility promise: a file written by one version has to stay
 * readable by the next, and a payload that happens to contain the separator byte must not be
 * able to impersonate a different header.
 */
class StorageRecordFileCodecTest {

    private val serializer = StringSerializer()

    @Test
    fun everyHeaderFieldSurvivesARoundTrip() {
        val record = StorageRecord.create("payload", serializer, createdAt = 1_700_000_000_000L, ttl = 90.seconds)
            .copy(lastAccessedAt = 1_700_000_045_000L)

        val decoded = StorageRecordFileCodec.decode(StorageRecordFileCodec.encode(record))

        assertEquals(1_700_000_000_000L, decoded.createdAt)
        assertEquals(90.seconds, decoded.ttl)
        assertEquals(1_700_000_045_000L, decoded.lastAccessedAt)
        assertContentEquals("payload".encodeToByteArray(), decoded.data)
    }

    @Test
    fun theHeaderIsWrittenInTheDocumentedOrder() {
        val record = StorageRecord.create("x", serializer, createdAt = 111L, ttl = 222.seconds)
            .copy(lastAccessedAt = 333L)

        // The payload is exactly "x", so dropping it leaves the header including its trailing
        // separator: version, createdAt, ttlMillis, lastAccessedAt, dataLength.
        val header = StorageRecordFileCodec.encode(record).decodeToString().removeSuffix("x")

        assertEquals("v2|111|222000|333|1|", header)
    }

    @Test
    fun aZeroTtlMeansNoExpiry() {
        val record = StorageRecord.create("x", serializer, createdAt = 1L, ttl = null)

        val decoded = StorageRecordFileCodec.decode(StorageRecordFileCodec.encode(record))

        assertNull(decoded.ttl, "0 in the ttl slot must decode back to null")
    }

    @Test
    fun aPayloadContainingTheSeparatorByteIsNotMisread() {
        // The payload is opaque binary and may contain the header separator. Decoding must take
        // the payload length from the header rather than scanning for separators.
        val payload = "a|b|c|d|1|2|3".encodeToByteArray()
        val record = StorageRecord(value = payload, data = payload, createdAt = 7L, ttl = null, lastAccessedAt = 7L)

        val decoded = StorageRecordFileCodec.decode(StorageRecordFileCodec.encode(record))

        assertContentEquals(payload, decoded.data)
        assertEquals(7L, decoded.createdAt)
    }

    @Test
    fun anEmptyPayloadRoundTrips() {
        val record = StorageRecord(value = ByteArray(0), data = ByteArray(0), createdAt = 5L, ttl = null)

        val decoded = StorageRecordFileCodec.decode(StorageRecordFileCodec.encode(record))

        assertEquals(0, decoded.data.size)
        assertEquals(5L, decoded.createdAt)
    }

    @Test
    fun aRecordWithoutAnAccessTimestampDecodesAsNeverAccessedAfterCreation() {
        val record = StorageRecord.create("x", serializer, createdAt = 42L, ttl = null)

        val decoded = StorageRecordFileCodec.decode(StorageRecordFileCodec.encode(record))

        assertEquals(42L, decoded.lastAccessedAt, "a record nobody read must rank by its write time")
    }

    @Test
    fun aFileFromAnOlderLayoutIsStillReadable() {
        // The four-field header that shipped before access tracking. It is told apart by the
        // leading version marker, not by counting separators: the payload is arbitrary bytes and
        // may contain one, so counting would be a guess.
        val legacy = "100|500|5|hello".encodeToByteArray()

        val decoded = StorageRecordFileCodec.decode(legacy)

        assertContentEquals("hello".encodeToByteArray(), decoded.data)
        assertEquals(100L, decoded.createdAt)
        assertEquals(500.milliseconds, decoded.ttl)
        assertEquals(
            100L,
            decoded.lastAccessedAt,
            "a record nobody read must rank by its write time, the same backfill the migration does",
        )
    }

    @Test
    fun aLegacyPayloadContainingSeparatorsIsNotMisread() {
        val legacy = "100|0|7|a|b|c|d".encodeToByteArray()

        val decoded = StorageRecordFileCodec.decode(legacy)

        assertContentEquals("a|b|c|d".encodeToByteArray(), decoded.data)
        assertEquals(100L, decoded.createdAt)
    }

    @Test
    fun aLegacyRecordWithNoTtlDecodesAsNonExpiring() {
        val legacy = "7|0|2|hi".encodeToByteArray()

        val decoded = StorageRecordFileCodec.decode(legacy)

        assertNull(decoded.ttl)
        assertContentEquals("hi".encodeToByteArray(), decoded.data)
    }

    @Test
    fun aTruncatedRecordIsRejected() {
        val truncated = "v2|1|0|1|100".encodeToByteArray() // declares 100 bytes, carries none

        assertFailsWith<IllegalArgumentException> { StorageRecordFileCodec.decode(truncated) }
    }

    @Test
    fun garbageIsRejected() {
        assertFailsWith<IllegalArgumentException> { StorageRecordFileCodec.decode(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun aDeclaredLengthLargerThanTheFileIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            StorageRecordFileCodec.decode("v2|1|0|1|999|short".encodeToByteArray())
        }
    }
}
