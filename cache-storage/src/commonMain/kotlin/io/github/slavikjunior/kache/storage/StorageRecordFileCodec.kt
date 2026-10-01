package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.StorageRecord

/**
 * Binary codec for on-disk [StorageRecord] payloads.
 *
 * Layout: `createdAt|ttlMillis|dataLength|data`
 * where `ttlMillis` is written as `0` to mean "no expiry".
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

    /**
     * Encodes [record] into its on-disk representation.
     *
     * @param record The record to encode. Only its `data`, `createdAt` and `ttlMillis` are written.
     * @return The encoded bytes.
     */
    fun encode(record: StorageRecord<*>): ByteArray {
        val ttlPart = record.ttlMillis?.toString() ?: NO_TTL.toString()
        val header = "${record.createdAt}$SEPARATOR$ttlPart$SEPARATOR${record.data.size}$SEPARATOR"
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
     * @throws IllegalArgumentException if the bytes do not match the expected format.
     */
    fun decode(bytes: ByteArray): StorageRecord<ByteArray> {
        var pos = 0

        val firstSep = indexOfSeparator(bytes, pos)
        val createdAt = bytes.decodeToString(pos, firstSep).toLong()
        pos = firstSep + 1

        val secondSep = indexOfSeparator(bytes, pos)
        val ttlPart = bytes.decodeToString(pos, secondSep)
        val ttlMillis = ttlPart.toLong().takeIf { it != NO_TTL }
        pos = secondSep + 1

        val thirdSep = indexOfSeparator(bytes, pos)
        val dataLength = bytes.decodeToString(pos, thirdSep).toInt()
        pos = thirdSep + 1

        if (dataLength < 0 || pos + dataLength > bytes.size) {
            throw IllegalArgumentException(
                "Invalid record format: declared data length $dataLength does not fit in ${bytes.size - pos} bytes"
            )
        }

        val data = bytes.copyOfRange(pos, pos + dataLength)
        return StorageRecord(value = data, data = data, createdAt = createdAt, ttlMillis = ttlMillis)
    }

    private fun indexOfSeparator(bytes: ByteArray, start: Int): Int {
        for (i in start until bytes.size) {
            if (bytes[i] == SEPARATOR.code.toByte()) return i
        }
        throw IllegalArgumentException("Invalid record format: missing '$SEPARATOR' separator at or after $start")
    }
}
