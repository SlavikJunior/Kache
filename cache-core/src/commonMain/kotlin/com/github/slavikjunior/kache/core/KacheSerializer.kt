package com.github.slavikjunior.kache.core

import com.github.slavikjunior.kache.core.KacheException

/**
 * Interface for serializing and deserializing values to/from bytes.
 * Implementations can use any serialization format (JSON, Protobuf, custom binary, etc.).
 *
 * @param T The type of value to serialize/deserialize.
 */
public interface KacheSerializer<T> {
    /**
     * Serializes the given value to a byte array.
     * @param value The value to serialize.
     * @return The serialized byte array.
     * @throws KacheException.SerializationException if serialization fails.
     */
    public fun serialize(value: T): ByteArray

    /**
     * Deserializes a byte array back to the value.
     * @param bytes The byte array to deserialize.
     * @return The deserialized value.
     * @throws KacheException.SerializationException if deserialization fails.
     */
    public fun deserialize(bytes: ByteArray): T
}