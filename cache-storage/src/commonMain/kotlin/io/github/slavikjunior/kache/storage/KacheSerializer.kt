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
        } catch (e: StackOverflowError) {
            // A user-supplied serializer may recurse without bound; that is a formatting
            // failure too, and must not escape as an Error.
            throw KacheException.SerializationException(e)
        }
    }

    override fun deserialize(bytes: ByteArray): T {
        // A payload on disk is untrusted input: whoever can write to the backend can plant a
        // document that costs more to parse than the caller meant to pay. The JSON parser
        // recurses, so nesting it can exhaust the stack before kotlinx.serialization gets to
        // report anything useful, so the depth is counted up front and the parse is refused.
        if (bytes.maxJsonNestingDepth() > MAX_NESTING_DEPTH) {
            throw KacheException.SerializationException(
                IllegalStateException("JSON nesting deeper than $MAX_NESTING_DEPTH levels"),
            )
        }
        return try {
            json.decodeFromString(serializer, bytes.decodeToString())
        } catch (e: Exception) {
            throw KacheException.SerializationException(e)
        } catch (e: StackOverflowError) {
            // Left for a user-supplied [serializer] that recurses on its own; the bracket
            // count above only guards the document this class parses itself.
            throw KacheException.SerializationException(e)
        }
    }

    private companion object {
        @Suppress("JSON_FORMAT_REDUNDANT")
        val json = Json { }
    }
}

/**
 * Maximum number of simultaneously open `[` or `{` in this document.
 *
 * Scanned byte-wise rather than decoded first, so nothing allocates before the size check.
 * The only bytes that matter are `"` (0x22) and `\` (0x5C), both of which are single-byte in
 * UTF-8 and never occur inside a multi-byte sequence, so a byte-wise pass cannot be fooled
 * by text inside a string. Returns 0 for a document whose brackets do not balance, leaving
 * the malformed input for the parser to report properly.
 */
/**
 * Levels of `[`/`{` accepted in one document. Deeper than this is refused before parsing,
 * rather than being allowed to walk off the end of the stack. Not part of the public API:
 * it is a guard against hostile bytes, not a contract callers depend on.
 */
private const val MAX_NESTING_DEPTH = 64

private const val QUOTE_BYTE: Byte = 0x22
private const val ESCAPE_BYTE: Byte = 0x5C
private const val OPEN_BRACKET: Byte = 0x5B
private const val CLOSE_BRACKET: Byte = 0x5D
private const val OPEN_BRACE: Byte = 0x7B
private const val CLOSE_BRACE: Byte = 0x7D

private fun ByteArray.maxJsonNestingDepth(): Int {
    var depth = 0
    var deepest = 0
    var inString = false
    var escaped = false

    for (byte in this) {
        if (inString) {
            when {
                escaped -> escaped = false
                byte == ESCAPE_BYTE -> escaped = true
                byte == QUOTE_BYTE -> inString = false
            }
            continue
        }

        when (byte) {
            QUOTE_BYTE -> inString = true
            OPEN_BRACKET, OPEN_BRACE -> {
                depth++
                if (depth > deepest) deepest = depth
            }
            CLOSE_BRACKET, CLOSE_BRACE -> {
                depth--
                if (depth < 0) return 0
            }
        }
    }

    return deepest
}