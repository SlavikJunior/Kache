package io.github.slavikjunior.kache.sample.ui.cars

import io.github.slavikjunior.kache.core.KacheException

/**
 * Turns a cache failure into something a screen may show.
 *
 * The mapping is exhaustive on purpose: [KacheException] is sealed, so a failure type added
 * to the library fails this `when` at compile time instead of reaching the user as a blank
 * screen. The cause is deliberately not shown — it is a transport detail, useful in a log,
 * not in a label.
 */
internal fun KacheException.toUserMessage(): String = when (this) {
    is KacheException.NetworkException -> "Network request failed"
    is KacheException.CacheMissException -> "Nothing cached yet"
    is KacheException.DiskReadException -> "Could not read the cache"
    is KacheException.DiskWriteException -> "Could not write the cache"
    is KacheException.SerializationException -> "Cached data could not be decoded"
    is KacheException.UnknownKacheException -> "Unexpected cache failure"
}
