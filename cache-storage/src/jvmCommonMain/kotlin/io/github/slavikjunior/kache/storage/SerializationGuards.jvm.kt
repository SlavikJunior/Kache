package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.KacheException

/**
 * JVM and Android both throw a catchable `StackOverflowError` when the parser recurses too
 * deeply, so it is translated into the same typed failure as any other formatting problem.
 */
internal actual fun <T> catchingStackOverflow(block: () -> T): T = try {
    block()
} catch (e: StackOverflowError) {
    throw KacheException.SerializationException(e)
}
