package io.github.slavikjunior.kache.storage

import java.io.File
import kotlin.random.Random

/**
 * Android host tests run on the JVM, so `java.io` is enough.
 *
 * `java.nio.file` is deliberately avoided even here: the module declares `minSdk 23`, and a
 * test that compiles only because the host JDK has API 26 would hide the production constraint
 * it is supposed to respect.
 */
internal actual fun newTestDirectory(name: String): String {
    val directory = File(
        System.getProperty("java.io.tmpdir"),
        "kache-$name-${Random.nextLong().toULong().toString(16)}",
    )
    require(directory.mkdirs() || directory.isDirectory) { "Cannot create $directory" }
    return directory.absolutePath
}
