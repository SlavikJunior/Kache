package io.github.slavikjunior.kache.storage

import java.io.File
import java.nio.file.Files

/** JVM: `createTempDirectory` gives a unique name the test does not have to invent. */
internal actual fun newTestDirectory(name: String): String =
    Files.createTempDirectory("kache-$name-").toFile().absolutePath
