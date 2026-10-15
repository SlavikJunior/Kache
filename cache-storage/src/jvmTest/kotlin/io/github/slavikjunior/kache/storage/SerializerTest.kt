package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.StorageRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

    // --- nesting depth on untrusted input ---

    @Test
    fun nestingWithinTheDepthLimitStillParses() {
        val json = KotlinxJsonSerializer(JsonElement.serializer())

        val parsed = json.deserialize(nest(32).encodeToByteArray())

        assertIs<JsonArray>(parsed)
    }

    @Test
    fun nestingDeeperThanTheDepthLimitIsRefused() {
        val json = KotlinxJsonSerializer(JsonElement.serializer())
        // Deep enough that parsing it would walk off the end of the stack rather than report
        // anything useful, which is the whole reason the depth is counted first.
        val hostile = nest(10_000)

        val thrown = assertFailsWith<KacheException.SerializationException> {
            json.deserialize(hostile.encodeToByteArray())
        }

        // The refusal has to be ours. Left to the parser this input exhausts the stack, and the
        // catch for that turns the resulting StackOverflowError into a SerializationException
        // too — same type the caller sees, so only the cause tells the two apart.
        assertIs<IllegalStateException>(thrown.cause, "cause was ${thrown.cause}")
        assertTrue(
            thrown.cause.message.orEmpty().contains("nesting"),
            "the message should say what was refused, was '${thrown.cause.message}'",
        )
    }

    @Test
    fun bracketsInsideAStringAreNotCountedAsNesting() {
        val json = KotlinxJsonSerializer(JsonElement.serializer())
        // Five thousand literal brackets, all inside one string value. A scanner that did not
        // track string state would refuse a document this shallow.
        val payload = """{"a":"${"[".repeat(5_000)}${"]".repeat(5_000)}"}"""

        val parsed = json.deserialize(payload.encodeToByteArray())

        assertIs<JsonObject>(parsed)
    }

    @Test
    fun aStackOverflowFromACallerSuppliedSerializerIsReportedAsAKacheFailure() {
        // The bracket count above only guards the document this class parses itself. A
        // serializer the caller hands in can recurse on its own, and an `Error` escaping
        // through the cache would take the caller down in a way no typed exception would.
        val json = KotlinxJsonSerializer(StackOverflowingSerializer)

        val thrown = assertFailsWith<KacheException.SerializationException> {
            json.deserialize("{}".encodeToByteArray())
        }

        assertIs<StackOverflowError>(thrown.cause, "cause was ${thrown.cause}")
    }

    /** Raises the Error a runaway serializer would raise, without actually running one. */
    private object StackOverflowingSerializer : KSerializer<Any?> {

        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("StackOverflowing", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Any? = throw StackOverflowError("synthetic")

        override fun serialize(encoder: Encoder, value: Any?) = Unit
    }

    /** `levels` nested JSON arrays, as the text a payload would hold. */
    private fun nest(levels: Int): String = "[".repeat(levels) + "]".repeat(levels)

    @Test
    fun serializerWorksThroughStorageRecord() {
        val original = Account("u-7", Address("Moscow", "101000"), listOf("vip"))

        val record = StorageRecord.create(original, account, createdAt = 42L, ttl = 10.milliseconds)
        val restored = account.deserialize(record.data)

        assertEquals(original, restored)
        assertEquals(42L, record.createdAt)
        assertEquals(52L, record.expiresAt())
    }
}