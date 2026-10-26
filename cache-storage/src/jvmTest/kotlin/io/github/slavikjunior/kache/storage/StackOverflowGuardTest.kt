package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.KacheException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * JVM-only half of the serializer's failure handling.
 *
 * A `StackOverflowError` is a JVM `Error`; Kotlin/Native has no such type and aborts the
 * process on stack exhaustion instead. The guard itself is `expect`/`actual`, so this test
 * can only exist here — see `SerializationGuards.jvm.kt` and `SerializationGuards.ios.kt`.
 */
class StackOverflowGuardTest {

    @Test
    fun aStackOverflowFromACallerSuppliedSerializerIsReportedAsAKacheFailure() {
        // The bracket count in KotlinxJsonSerializer only guards the document this class
        // parses itself. A serializer the caller hands in can recurse on its own, and an
        // `Error` escaping through the cache would take the caller down in a way no typed
        // exception would.
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
}
