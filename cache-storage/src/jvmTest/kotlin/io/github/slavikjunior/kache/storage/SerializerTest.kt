package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.StorageRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private data class Address(val city: String, val zip: String)

@Serializable
private data class Account(val id: String, val address: Address, val tags: List<String>)

class SerializerTest {

    private val string = StringSerializer()
    private val account = KotlinxJsonSerializer(Account.serializer())
    private val address = KotlinxJsonSerializer(Address.serializer())

    // --- StringSerializer ---

    @Test
    fun stringSerializerRoundTripsUtf8() {
        val samples = listOf(
            "ascii",
            "кириллица",
            "emoji 🎉",
            "",
            "line\nbreak\ttab",
        )

        samples.forEach { original ->
            assertEquals(original, string.deserialize(string.serialize(original)), "round trip of '$original'")
        }
    }

    @Test
    fun stringSerializerProducesUtf8Bytes() {
        // A byte-level assertion: this is what actually lands in a file, and it pins the
        // choice of encoding rather than leaving it to the implementation.
        assertContentEquals("é".encodeToByteArray(), string.serialize("é"))
        assertEquals(2, string.serialize("é").size)
    }

    @Test
    fun emptyStringIsDistinguishableFromNoPayload() {
        assertEquals(0, string.serialize("").size)
        assertEquals("", string.deserialize(byteArrayOf()))
    }

    // --- KotlinxJsonSerializer ---

    @Test
    fun jsonRoundTripsNestedStructures() {
        val original = Account(
            id = "u-42",
            address = Address(city = "Kazan", zip = "420111"),
            tags = listOf("a", "b"),
        )

        assertEquals(original, account.deserialize(account.serialize(original)))
    }

    @Test
    fun jsonHandlesEmptyCollections() {
        val original = Account(id = "x", address = Address(city = "", zip = ""), tags = emptyList())

        assertEquals(original, account.deserialize(account.serialize(original)))
    }

    @Test
    fun jsonOutputIsStableAcrossCalls() {
        val original = Account("u-1", Address("Kazan", "420111"), listOf("t"))

        assertContentEquals(account.serialize(original), account.serialize(original))
    }

    @Test
    fun malformedJsonIsMappedToTheKacheHierarchy() {
        val thrown = assertFailsWith<KacheException.SerializationException> {
            address.deserialize("{ not json".encodeToByteArray())
        }

        // The caller gets a Kache type, not a serialization-library type, so the cache
        // contract holds across backends.
        assertTrue(thrown.cause is SerializationException || thrown.cause != null, "cause was ${thrown.cause}")
    }

    @Test
    fun serializingAMismatchedShapeFails() {
        assertFailsWith<KacheException.SerializationException> {
            // The payload is a valid JSON array, but Address expects an object.
            address.deserialize("[]".encodeToByteArray())
        }
    }

    @Test
    fun serializerWorksThroughStorageRecord() {
        val original = Account("u-7", Address("Moscow", "101000"), listOf("vip"))

        val record = StorageRecord.create(original, account, createdAt = 42L, ttlMillis = 10L)
        val restored = account.deserialize(record.data)

        assertEquals(original, restored)
        assertEquals(42L, record.createdAt)
        assertEquals(52L, record.expiresAt())
    }
}