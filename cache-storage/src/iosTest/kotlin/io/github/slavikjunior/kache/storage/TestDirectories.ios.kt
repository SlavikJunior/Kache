package io.github.slavikjunior.kache.storage

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/** iOS: the system temporary directory plus a random suffix, created before use. */
internal actual fun newTestDirectory(name: String): String {
    val path = Path(
        SystemTemporaryDirectory.toString(),
        "kache-$name-${Random.nextLong().toULong().toString(16)}",
    )
    SystemFileSystem.createDirectories(path)
    return path.toString()
}
