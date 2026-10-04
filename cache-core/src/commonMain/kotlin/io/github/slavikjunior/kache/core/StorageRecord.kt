package io.github.slavikjunior.kache.core

import kotlin.time.Duration

/**
 * A single cache record.
 *
 * The same record type serves both tiers, which is why it carries two representations
 * of the payload:
 * - [value] — the live, already deserialized value. Used by L1 so that in-memory reads
 *   never pay for serialization.
 * - [data] — the serialized byte payload. Used by L2 for persistence and transport.
 *
 * @param V The value type held by this record.
 * @param value The live value. L1 reads this directly.
 * @param data The serialized payload. L2 persists this. Empty for pure L1 entries.
 * @param createdAt Timestamp in milliseconds since epoch when the record was written.
 * @param ttl How long the record stays fresh. Null means it never expires. Durations that
 *   are zero or negative make the record expire immediately, which is almost never what
 *   the caller meant; the tier implementations are where that is normalised.
 * @param lastAccessedAt Timestamp in milliseconds since epoch of the last read. Defaults
 *   to [createdAt], which makes a freshly written record the most recently used one.
 *   L2 needs this to order records for [EvictionStrategy.LRU]; see
 *   [StorageEngine.touch] for how it is kept current without turning every read into a write.
 */
public data class StorageRecord<out V>(
    public val value: V,
    public val data: ByteArray = byteArrayOf(),
    public val createdAt: Long,
    public val ttl: Duration? = null,
    public val lastAccessedAt: Long = createdAt,
) {
    /**
     * Checks whether this record has expired.
     *
     * Time must be passed explicitly so that callers stay in control of the clock
     * (and so tests can use a fake one).
     *
     * @param currentTimeMillis The current time in milliseconds since epoch.
     * @return true if the record is expired, false otherwise.
     */
    public fun isExpired(currentTimeMillis: Long): Boolean {
        val lifetime = ttl ?: return false
        return currentTimeMillis - createdAt > lifetime.inWholeMilliseconds
    }

    /**
     * Timestamp at which this record expires, or null if it never expires.
     *
     * @return Absolute expiration timestamp in milliseconds since epoch, or null.
     */
    public fun expiresAt(): Long? = ttl?.let { createdAt + it.inWholeMilliseconds }

    public companion object {
        /**
         * Creates a record holding both the live value and its serialized payload.
         *
         * @param value The value to store.
         * @param serializer Serializer used to produce the persisted [StorageRecord.data].
         * @param createdAt Timestamp in milliseconds since epoch when the record was written.
         * @param ttl Optional time-to-live.
         * @throws KacheException.SerializationException if serialization fails.
         */
        public fun <T> create(
            value: T,
            serializer: KacheSerializer<T>,
            createdAt: Long,
            ttl: Duration? = null,
        ): StorageRecord<T> = StorageRecord(
            value = value,
            data = serializer.serialize(value),
            createdAt = createdAt,
            ttl = ttl,
            lastAccessedAt = createdAt,
        )

        /**
         * Restores a record from a persisted payload.
         *
         * @param value The value decoded from [data].
         * @param data The serialized payload as read from L2.
         * @param createdAt Timestamp in milliseconds since epoch when the record was written.
         * @param ttl Optional time-to-live.
         * @param lastAccessedAt Timestamp in milliseconds since epoch of the last read.
         */
        public fun <T> fromData(
            value: T,
            data: ByteArray,
            createdAt: Long,
            ttl: Duration? = null,
            lastAccessedAt: Long = createdAt,
        ): StorageRecord<T> = StorageRecord(
            value = value,
            data = data,
            createdAt = createdAt,
            ttl = ttl,
            lastAccessedAt = lastAccessedAt,
        )
    }
}