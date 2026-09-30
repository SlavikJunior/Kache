package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.KacheException
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinxJsonSerializerJvmTest {

    @Test
    fun `serializes and deserializes strings`() {
        val serializer = KotlinxJsonSerializer<String>(serializer<String>())
        val original = "hello world"
        
        val bytes = serializer.serialize(original)
        val result = serializer.deserialize(bytes)
        
        assertEquals(original, result)
    }

    @Test
    fun `serializes and deserializes data classes`() {
        @Serializable
        data class User(val name: String, val age: Int)
        
        val serializer = KotlinxJsonSerializer<User>(serializer<User>())
        val original = User("Alice", 30)
        
        val bytes = serializer.serialize(original)
        val result = serializer.deserialize(bytes)
        
        assertEquals(original, result)
    }

    @Test
    fun `deserialization throws on corrupted data`() {
        val serializer = KotlinxJsonSerializer<String>(serializer<String>())
        val corruptedBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()) // Invalid JSON
        
        var exception: KacheException.SerializationException? = null
        try {
            serializer.deserialize(corruptedBytes)
        } catch (e: KacheException.SerializationException) {
            exception = e
        }
        
        assertNotNull(exception)
    }
}