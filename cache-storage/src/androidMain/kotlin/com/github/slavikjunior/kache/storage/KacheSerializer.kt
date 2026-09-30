package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.KacheException
import com.github.slavikjunior.kache.core.KacheSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.text.Charsets

actual class StringSerializer : KacheSerializer<String> {
    override fun serialize(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    override fun deserialize(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
}

actual class KotlinxJsonSerializer<T> actual constructor(
    private val serializer: KSerializer<T>
) : KacheSerializer<T> {

    private val json = Json {}

    override fun serialize(value: T): ByteArray {
        return try {
            json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            throw KacheException.SerializationException(e)
        }
    }

    override fun deserialize(bytes: ByteArray): T {
        return try {
            json.decodeFromString(serializer, String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw KacheException.SerializationException(e)
        }
    }
}