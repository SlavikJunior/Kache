package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.KacheException
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The nesting guard, by depth rather than by example.
 *
 * A cache payload is untrusted input, and the JSON parser recurses: a document nested deeply
 * enough exhausts the stack before the parser can report anything useful. The guard counts
 * brackets up front and refuses the document, so the failure is a typed cache error at any
 * depth — including depths that would crash the process if they reached the parser.
 *
 * Runs on every target: the guard is pure byte work, and it has to hold on iOS, where a stack
 * overflow aborts the process instead of raising something catchable.
 */
class JsonNestingDepthTest {

    private val serializer = KotlinxJsonSerializer(JsonElement.serializer())

    @Test
    fun documentsWithinTheDepthLimitParse() {
        listOf(1, 2, 16, 63, 64).forEach { depth ->
            val json = "[".repeat(depth) + "]".repeat(depth)

            val decoded = serializer.deserialize(json.encodeToByteArray())

            assertIs<JsonElement>(decoded, "depth $depth must be accepted")
        }
    }

    @Test
    fun documentsDeeperThanTheLimitAreRefusedBeforeParsing() {
        // 100_000 is not a stress test for the parser but for the counter: it has to be a
        // single pass with no recursion of its own, or the guard itself becomes the overflow.
        listOf(65, 1_000, 100_000).forEach { depth ->
            val json = "[".repeat(depth) + "]".repeat(depth)

            val failure = assertFailsWith<KacheException.SerializationException>("depth $depth") {
                serializer.deserialize(json.encodeToByteArray())
            }
            assertEquals(
                "JSON nesting deeper than 64 levels",
                failure.cause?.message,
                "depth $depth must be refused by the guard, not by the parser",
            )
        }
    }

    @Test
    fun bracketsInsideStringsDoNotCountTowardsTheDepth() {
        // The counter is byte-wise, so it has to honour quoting and escapes: otherwise a
        // perfectly ordinary payload mentioning "[[[[" would be refused.
        val json = """["[[[[[[[[[[[[", "\\", "{\"a\":1}"]"""

        val decoded = serializer.deserialize(json.encodeToByteArray())

        assertIs<JsonElement>(decoded)
    }
}
