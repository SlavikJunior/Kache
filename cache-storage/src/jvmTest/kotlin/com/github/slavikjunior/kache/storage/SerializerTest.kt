package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.KacheException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StringSerializerTest {

    private val serializer = StringSerializer()

    @Test
    fun `round-trips a plain string`() {
        assertEquals("hello world", serializer.deserialize(serializer.serialize("hello world")))
    }

    @Test
    fun `round-trips an empty string`() {
        assertEquals("", serializer.deserialize(serializer.serialize("")))
    }

    @Test
    fun `round-trips non-latin text`() {
        val original = "你好世界 🌍"

        assertEquals(original, serializer.deserialize(serializer.serialize(original)))
    }
}

class KotlinxJsonSerializerTest {

    @Serializable
    private data class User(val name: String, val age: Int)

    private val userSerializer = KotlinxJsonSerializer(User.serializer())

    @Test
    fun `round-trips a data class`() {
        val original = User("Alice", 30)

        assertEquals(original, userSerializer.deserialize(userSerializer.serialize(original)))
    }

    @Test
    fun `round-trips a list`() {
        val listSerializer = KotlinxJsonSerializer(ListSerializer(User.serializer()))

        val original = listOf(User("Alice", 30), User("Bob", 25))

        assertEquals(original, listSerializer.deserialize(listSerializer.serialize(original)))
    }

    @Test
    fun `malformed JSON is reported as a typed exception`() {
        val stringJson = KotlinxJsonSerializer(serializer<String>())

        assertFailsWith<KacheException.SerializationException> {
            stringJson.deserialize(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        }
    }
}