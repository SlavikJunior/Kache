package io.github.slavikjunior.kache.storage

import kotlin.time.Duration.Companion.milliseconds

import io.github.slavikjunior.kache.core.StorageRecord

/**
 * Binary codec for on-disk [StorageRecord] payloads.
 *
 * Layout: `v2|createdAt|ttlMillis|lastAccessedAt|dataLength|data`
 * where `ttlMillis` is written as `0` to mean "no expiry" and `lastAccessedAt` is the
 * access timestamp LRU ordering depends on.
 *
 * This lives in `commonMain` because it is pure Kotlin over [ByteArray] and [String],
 * so every platform can share one file format instead of each backend re-deriving it.
 * Only the file access itself is platform specific.
 *
 * The leading version marker is deliberate rather than clever. The payload is opaque
 * binary that may itself contain the `|` byte, so there is no way to tell the old
 * four-field header from the new five-field one by counting separators. Guessing would
 * risk decoding a record with a wrong access timestamp. Instead [decode] rejects any
 * version it does not know, and [io.github.slavikjunior.kache.core.StorageEngine.get]
 * treats that as "record absent" and deletes the file — a cache directory is disposable,
 * so a format bump costs one cold start and no data anyone can lose.
 *
 * Records are returned with their payload as the record value, since a persisted record
 * has no live object attached to it. The caller's serializer is what turns the bytes back
 * into a typed value.
 */
internal object StorageRecordFileCodec {

    private const val SEPARATOR = '|'
    private const val NO_TTL = 0L

    /** Bump together with the layout above whenever the header changes shape. */
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
     * Decodes bytes produced by [encode].
     *
     * @param bytes The encoded bytes.
     * @return The decoded record, with its payload exposed as the record value.
     * @throws IllegalArgumentException if the bytes do not match the expected format,
     *   including when they were written by an older, incompatible layout.
     */
    fun decode(bytes: ByteArray): StorageRecord<ByteArray> {
        var pos = 0

        val versionSep = indexOfSeparator(bytes, pos)
        val version = bytes.decodeToString(pos, versionSep)
        if (version != FORMAT_VERSION) {
            throw IllegalArgumentException(
                "Unsupported record format version '$version', expected '$FORMAT_VERSION'"
            )
        }
        pos = versionSep + 1

        val createdAtSep = indexOfSeparator(bytes, pos)
        val createdAt = bytes.decodeToString(pos, createdAtSep).toLong()
        pos = createdAtSep + 1

        val ttlSep = indexOfSeparator(bytes, pos)
        val ttlPart = bytes.decodeToString(pos, ttlSep)
        val ttl = ttlPart.toLong().takeIf { it != NO_TTL }?.milliseconds
        pos = ttlSep + 1

        val accessSep = indexOfSeparator(bytes, pos)
        val lastAccessedAt = bytes.decodeToString(pos, accessSep).toLong()
        pos = accessSep + 1

        val lengthSep = indexOfSeparator(bytes, pos)
        val dataLength = bytes.decodeToString(pos, lengthSep).toInt()
        pos = lengthSep + 1

        if (dataLength < 0 || pos + dataLength > bytes.size) {
            throw IllegalArgumentException(
                "Invalid record format: declared data length $dataLength does not fit in ${bytes.size - pos} bytes"
            )
        }

        val data = bytes.copyOfRange(pos, pos + dataLength)
        return StorageRecord(
            value = data,
            data = data,
            createdAt = createdAt,
            ttl = ttl,
            lastAccessedAt = lastAccessedAt,
        )
    }

    private fun indexOfSeparator(bytes: ByteArray, start: Int): Int {
        for (i in start until bytes.size) {
            if (bytes[i] == SEPARATOR.code.toByte()) return i
        }
        throw IllegalArgumentException("Invalid record format: missing '$SEPARATOR' separator at or after $start")
    }
}
