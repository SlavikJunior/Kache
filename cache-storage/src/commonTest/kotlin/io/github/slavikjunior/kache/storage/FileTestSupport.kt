package io.github.slavikjunior.kache.storage

/**
 * File-system helpers for tests, on top of the same [FileOps] the engine uses.
 *
 * Going through the seam instead of a platform API is what lets a test on iOS and a test on
 * the JVM set up and inspect a directory identically — and it exercises the production
 * implementation on every target rather than a test-only one.
 */
internal object FileTestSupport {

    private val files: FileOps = platformFileOps()

    /** Writes [path] with the same temp-then-move discipline the engine uses. */
    fun writeFile(path: String, bytes: ByteArray) {
        files.writeAtomically(
            target = path,
            directory = path.substringBeforeLast('/'),
            bytes = bytes,
            tempPrefix = "test-",
            tempSuffix = ".tmp",
        )
    }

    fun exists(path: String): Boolean = files.exists(path)

    fun readBytes(path: String): ByteArray = files.readBytes(path)

    fun namesIn(directory: String): List<String> = files.list(directory).map { it.name }

    fun sizeBytes(path: String): Long? = files.sizeBytes(path)
}
