package io.github.slavikjunior.kache.storage

/**
 * Runs [block], reporting an exhausted stack as a cache serialization failure where the
 * platform allows catching one.
 *
 * The JSON parsers recurse, so a hostile document — or a caller's own serializer that
 * recurses without bound — can exhaust the stack. On the JVM that surfaces as
 * `StackOverflowError`, which is an `Error` and therefore slips past the `catch (Exception)`
 * wrapped around the parse. Kotlin/Native aborts the process instead of throwing, so there
 * is nothing to catch and the actual is a plain invocation; `StackOverflowError` itself does
 * not resolve outside the JVM, which is why this indirection exists at all.
 */
internal expect fun <T> catchingStackOverflow(block: () -> T): T
