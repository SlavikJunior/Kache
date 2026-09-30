package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.KacheSerializer

/**
 * A simple string serializer for String values.
 * Uses UTF-8 encoding.
 */
public expect class StringSerializer : KacheSerializer<String>

/**
 * A serializer that uses kotlinx.serialization for JSON encoding/decoding.
 * Requires the type [T] to be annotated with @Serializable.
 */
public expect class KotlinxJsonSerializer<T>(
    serializer: kotlinx.serialization.KSerializer<T>
) : KacheSerializer<T>