package io.github.slavikjunior.kache.storage

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

import io.github.slavikjunior.kache.core.StorageRecord

/**
 * Binary codec for on-disk [StorageRecord] payloads.
 *
 * Current layout: `v2|createdAt|ttlMillis|lastAccessedAt|dataLength|data`
 * where `ttlMillis` is written as `0` to mean "no expiry" and `lastAccessedAt` is the
 * access timestamp LRU ordering depends on.
 *
 * Layouts written before access tracking existed are still read:
 * `createdAt|ttlMillis|dataLength|data`. They are told apart by the leading [FORMAT_VERSION]
 * marker rather than by counting separators, which cannot work — the payload is arbitrary
 * binary and may itself contain the separator byte. A legacy record is read with its
 * `lastAccessedAt` set to its `createdAt`, the same backfill the database migration performs,
 * so records written by either layout rank identically until they are read again.
 *
 * This lives in `commonMain` because it is pure Kotlin over [ByteArray] and [String],
 * so every platform can share one file format instead of each backend re-deriving it.
 * Only the file access itself is platform specific.
 *
 * Records are returned with their payload as the record value, since a persisted record
 * has no live object attached to it. The caller's serializer is what turns the bytes back
 * into a typed value.
 */
internal object StorageRecordFileCodec {

    private const val SEPARATOR = '|'
    private const val NO_TTL = 0L

    /** Bump together with the layout below whenever the header changes shape. */
    private const val FORMAT_VERSION = "v2"

    /**
     * Encodes [record] into its on-disk representation.
     *
     * @param record The record to encode. Its `data`, `createdAt`, `ttl` and
     *   `lastAccessedAt` are written, the durations as whole milliseconds.
     * @return The encoded bytes.
     */
    fun encode(record: StorageRecord<*>): ByteArray {
        // The on-disk layout stays in milliseconds: it is a wire format, and durations
        // would only add an encoding convention without telling a reader anything new.
        val ttlPart = record.ttl?.inWholeMilliseconds?.toString() ?: NO_TTL.toString()
        val header = buildString {
            append(FORMAT_VERSION); append(SEPARATOR)
            append(record.createdAt); append(SEPARATOR)
            append(ttlPart); append(SEPARATOR)
            append(record.lastAccessedAt); append(SEPARATOR)
            append(record.data.size); append(SEPARATOR)
        }
        val headerBytes = header.encodeToByteArray()

        return ByteArray(headerBytes.size + record.data.size).also { out ->
            headerBytes.copyInto(out, destinationOffset = 0)
            record.data.copyInto(out, destinationOffset = headerBytes.size)
        }
    }

    /**
     * Decodes bytes produced by [encode], or by an earlier layout this codec still reads.
     *
     * @param bytes The encoded bytes.
     * @return The decoded record, with its payload exposed as the record value.
     * @throws IllegalArgumentException if the bytes match neither layout.
     */
    fun decode(bytes: ByteArray): StorageRecord<ByteArray> {
        val firstSeparator = indexOfSeparator(bytes, 0)
        val firstField = bytes.decodeToString(0, firstSeparator)

        return if (firstField == FORMAT_VERSION) decodeCurrent(bytes, firstSeparator + 1) else {
            // Legacy layout: `createdAt|ttlMillis|dataLength|data`, with the access time
            // backfilled from the creation time.
            val createdAt = firstField.toLong()
            var pos = firstSeparator + 1

            val ttlMillis = readField(bytes, pos).also { pos = it.second }
            val dataLength = readField(bytes, pos)
            val data = readPayload(bytes, dataLength.second, dataLength.first.toInt())

            StorageRecord(
                value = data,
                data = data,
                createdAt = createdAt,
                ttl = ttlMillis.first.asTtl(),
                lastAccessedAt = createdAt,
            )
        }
    }

    /** Reads `createdAt|ttlMillis|lastAccessedAt|dataLength|data`. */
    private fun decodeCurrent(bytes: ByteArray, start: Int): StorageRecord<ByteArray> {
        var pos = start

        val (createdAt, afterCreatedAt) = readField(bytes, pos)
        pos = afterCreatedAt

        val (ttlMillis, afterTtl) = readField(bytes, pos)
        pos = afterTtl

        val (lastAccessedAt, afterAccess) = readField(bytes, pos)
        pos = afterAccess

        val (declaredLength, afterLength) = readField(bytes, pos)
        val data = readPayload(bytes, afterLength, declaredLength.toInt())

        return StorageRecord(
            value = data,
            data = data,
            createdAt = createdAt,
            ttl = ttlMillis.asTtl(),
            lastAccessedAt = lastAccessedAt,
        )
    }

    /**
     * Reads one numeric field, returning its value and the offset just past its separator.
     */
    private fun readField(bytes: ByteArray, start: Int): Pair<Long, Int> {
        val separator = indexOfSeparator(bytes, start)
        return bytes.decodeToString(start, separator).toLong() to separator + 1
    }

    private fun readPayload(bytes: ByteArray, start: Int, declaredLength: Int): ByteArray {
        if (declaredLength < 0 || start + declaredLength > bytes.size) {
            throw IllegalArgumentException(
                "Invalid record format: declared data length $declaredLength does not fit in ${bytes.size - start} bytes"
            )
        }
        return bytes.copyOfRange(start, start + declaredLength)
    }

    /** `0` in the ttl slot means "no expiry"; anything else is a whole number of milliseconds. */
    private fun Long.asTtl(): Duration? = takeIf { it != NO_TTL }?.milliseconds

    private fun indexOfSeparator(bytes: ByteArray, start: Int): Int {
        for (i in start until bytes.size) {
            if (bytes[i] == SEPARATOR.code.toByte()) return i
        }
        throw IllegalArgumentException("Invalid record format: missing '$SEPARATOR' separator at or after $start")
    }
}
