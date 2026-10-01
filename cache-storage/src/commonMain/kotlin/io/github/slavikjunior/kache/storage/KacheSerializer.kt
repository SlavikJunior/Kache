package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.KacheSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * A simple string serializer for String values.
 * Uses UTF-8 encoding.
 */
public class StringSerializer : KacheSerializer<String> {

    override fun serialize(value: String): ByteArray = value.encodeToByteArray()

    override fun deserialize(bytes: ByteArray): String = bytes.decodeToString()
}

/**
 * A serializer that uses kotlinx.serialization for JSON encoding/decoding.
 * Requires the type [T] to be annotated with @Serializable.
 *
 * Lives in `commonMain` on purpose: `kotlinx.serialization.json.Json` and the
 * `encodeToByteArray()`/`decodeToString()` pair are available in common code, so this
 * implementation never needed an `expect`/`actual` pair. Formatting failures are reported
 * as [KacheException.SerializationException].
 *
 * UTF-8 is used explicitly and portably: `String.toByteArray(Charsets.UTF_8)` and
 * `kotlin.text.Charsets` do not resolve on Kotlin/Native, whereas `encodeToByteArray()`
 * and `decodeToString()` are UTF-8 on every target.
 */
public class KotlinxJsonSerializer<T>(
    private val serializer: KSerializer<T>
) : KacheSerializer<T> {

    override fun serialize(value: T): ByteArray {
        return try {
            json.encodeToString(serializer, value).encodeToByteArray()
        } catch (e: Exception) {
            throw KacheException.SerializationException(e)
        }
    }

    override fun deserialize(bytes: ByteArray): T {
        return try {
            json.decodeFromString(serializer, bytes.decodeToString())
        } catch (e: Exception) {
            throw KacheException.SerializationException(e)
        }
    }

    private companion object {
        @Suppress("JSON_FORMAT_REDUNDANT")
        val json = Json { }
    }
}