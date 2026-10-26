package io.github.slavikjunior.kache.storage

/**
 * Kotlin/Native has no catchable stack-overflow error: the process aborts instead, so there
 * is nothing to translate and the guard reduces to a plain invocation. The nesting-depth
 * check in [KotlinxJsonSerializer.deserialize] still runs on this platform, and it is what
 * refuses a hostile document before the parser ever recurses.
 */
internal actual fun <T> catchingStackOverflow(block: () -> T): T = block()
