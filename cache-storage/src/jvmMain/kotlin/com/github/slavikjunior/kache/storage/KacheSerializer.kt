package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.KacheException
import com.github.slavikjunior.kache.core.KacheSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.text.Charsets

public actual class StringSerializer : KacheSerializer<String> {
    override fun serialize(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    override fun deserialize(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
}

public actual class KotlinxJsonSerializer<T> actual constructor(
    private val serializer: KSerializer<T>
) : KacheSerializer<T> {

    override fun serialize(value: T): ByteArray {
        return try {
            val jsonString = json.encodeToString(serializer, value)
            jsonString.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            throw KacheException.SerializationException(e)
        }
    }

    override fun deserialize(bytes: ByteArray): T {
        return try {
            val jsonString = String(bytes, Charsets.UTF_8)
            json.decodeFromString(serializer, jsonString)
        } catch (e: Exception) {
            throw KacheException.SerializationException(e)
        }
    }

    private companion object {
        @Suppress("JSON_FORMAT_REDUNDANT")
        val json = Json { }
    }
}