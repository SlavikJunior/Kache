package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.StorageRecord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Property tests for the on-disk record format.
 *
 * The example-based tests next to this one pin specific documents; these pin the *shape* of the
 * format, which is what a cache actually depends on: whatever bytes arrive, the decoder either
 * returns a record or refuses it as malformed — never a `IndexOutOfBoundsException`, never a
 * negative-size allocation, never an out-of-memory. A cache directory is attacker-influenced
 * input once anything else can write to it, so "refuses" is the property that matters, and it
 * has to hold for inputs nobody thought of.
 *
 * Seeds are fixed, so a failure reproduces exactly; the loop reports the seed it failed on
 * instead of leaving the reader to guess.
 */
class StorageRecordFileCodecPropertyTest {

    @Test
    fun everyRandomRecordRoundTrips() {
        val random = Random(SEED)
        repeat(ITERATIONS) { iteration ->
            val record = randomRecord(random)
            val decoded = StorageRecordFileCodec.decode(StorageRecordFileCodec.encode(record))

            assertContentEquals(record.data, decoded.data, "iteration $iteration")
            assertEquals(record.createdAt, decoded.createdAt, "iteration $iteration")
            assertEquals(record.ttl, decoded.ttl, "iteration $iteration")
            assertEquals(record.lastAccessedAt, decoded.lastAccessedAt, "iteration $iteration")
        }
    }

    @Test
    fun everyTruncationIsRefusedRatherThanMisread() {
        // The strongest form of the length guard: no prefix of a valid record may decode to
        // something else, and none may fail with anything but a format error. A decoder that
        // reads a truncated payload as a shorter value would hand the cache a half-value.
        val record = randomRecord(Random(SEED + 1), payloadSize = 64)
        val bytes = StorageRecordFileCodec.encode(record)

        for (length in 0 until bytes.size) {
            val truncated = bytes.copyOfRange(0, length)
            val failure = runCatching { StorageRecordFileCodec.decode(truncated) }.exceptionOrNull()
            assertTrue(
                failure is IllegalArgumentException,
                "truncating to $length bytes produced ${failure ?: "a record"}, expected a format error",
            )
        }
    }

    @Test
    fun aDeclaredLengthLongerThanThePayloadIsRefusedWithoutNarrowing() {
        // 2^32 narrows to 0 as an Int and Long.MAX_VALUE wraps when added to an offset: both
        // were real bugs in this space, and both are invisible to an example that uses a
        // plausible size.
        listOf("4294967296", "9223372036854775807", "2147483648", "-1").forEach { declared ->
            val bytes = "v2|1|0|2|$declared|payload".encodeToByteArray()

            assertFailsWith<IllegalArgumentException>("declared length $declared") {
                StorageRecordFileCodec.decode(bytes)
            }
        }
    }

    @Test
    fun aDeclaredLengthShorterThanThePayloadReadsExactlyTheDeclaredBytes() {
        // Trailing bytes must be ignored, not returned: a payload is opaque, and reading past
        // the declared end would splice one record into another if a writer ever appended.
        val decoded = StorageRecordFileCodec.decode("v2|1|0|2|3|abcdef".encodeToByteArray())

        assertContentEquals("abc".encodeToByteArray(), decoded.data)
    }

    @Test
    fun arbitraryBytesAreRefusedAsMalformed() {
        val random = Random(SEED + 2)

        repeat(ITERATIONS) { iteration ->
            val bytes = random.nextBytes(random.nextInt(0, 96))
            val outcome = runCatching { StorageRecordFileCodec.decode(bytes) }

            // Decoding random bytes can succeed by accident — a four-field header is cheap to
            // spell — so the contract is "a record or a format error", not "always an error".
            outcome.exceptionOrNull()?.let { failure ->
                assertTrue(
                    failure is IllegalArgumentException,
                    "iteration $iteration decoded ${bytes.size} random bytes and threw $failure",
                )
            }
        }
    }

    @Test
    fun theSeparatorInsideAPayloadIsNeverMistakenForAFieldBoundary() {
        val random = Random(SEED + 3)

        repeat(64) {
            val payload = random.nextBytes(random.nextInt(1, 128))
            // Put separators where a naive `split('|')` would misread them.
            payload[0] = '|'.code.toByte()
            payload[payload.lastIndex] = '|'.code.toByte()
            val record = StorageRecord(data = payload, value = payload, createdAt = 1L, ttl = null, lastAccessedAt = 2L)

            assertContentEquals(payload, StorageRecordFileCodec.decode(StorageRecordFileCodec.encode(record)).data)
        }
    }

    private fun randomRecord(random: Random, payloadSize: Int = random.nextInt(0, 128)): StorageRecord<ByteArray> {
        val payload = random.nextBytes(payloadSize)
        // Half the records expire, so both header layouts of the ttl field are exercised.
        val ttl: Duration? = if (random.nextBoolean()) {
            random.nextLong(0, 30L * 24 * 60 * 60 * 1000).milliseconds
        } else {
            null
        }
        return StorageRecord(
            value = payload,
            data = payload,
            createdAt = random.nextLong(0, 4_000_000_000_000L),
            ttl = ttl,
            lastAccessedAt = random.nextLong(0, 4_000_000_000_000L),
        )
    }

    private companion object {
        const val SEED = 20261026
        const val ITERATIONS = 2000
    }
}
