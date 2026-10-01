package io.github.slavikjunior.kache.core

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
 * @param ttlMillis Optional time-to-live in milliseconds. Null means the record never expires.
 */
public data class StorageRecord<out V>(
    public val value: V,
    public val data: ByteArray = byteArrayOf(),
    public val createdAt: Long,
    public val ttlMillis: Long? = null,
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
    public fun isExpired(currentTimeMillis: Long): Boolean =
        ttlMillis?.let { (currentTimeMillis - createdAt) > it } ?: false

    /**
     * Timestamp at which this record expires, or null if it never expires.
     *
     * @return Absolute expiration timestamp in milliseconds since epoch, or null.
     */
    public fun expiresAt(): Long? = ttlMillis?.let { createdAt + it }

    public companion object {
        /**
         * Creates a record holding both the live value and its serialized payload.
         *
         * @param value The value to store.
         * @param serializer Serializer used to produce the persisted [StorageRecord.data].
         * @param createdAt Timestamp in milliseconds since epoch when the record was written.
         * @param ttlMillis Optional time-to-live in milliseconds.
         * @throws KacheException.SerializationException if serialization fails.
         */
        public fun <T> create(
            value: T,
            serializer: KacheSerializer<T>,
            createdAt: Long,
            ttlMillis: Long? = null,
        ): StorageRecord<T> = StorageRecord(
            value = value,
            data = serializer.serialize(value),
            createdAt = createdAt,
            ttlMillis = ttlMillis,
        )

        /**
         * Restores a record from a persisted payload.
         *
         * @param value The value decoded from [data].
         * @param data The serialized payload as read from L2.
         * @param createdAt Timestamp in milliseconds since epoch when the record was written.
         * @param ttlMillis Optional time-to-live in milliseconds.
         */
        public fun <T> fromData(
            value: T,
            data: ByteArray,
            createdAt: Long,
            ttlMillis: Long? = null,
        ): StorageRecord<T> = StorageRecord(
            value = value,
            data = data,
            createdAt = createdAt,
            ttlMillis = ttlMillis,
        )
    }
}
