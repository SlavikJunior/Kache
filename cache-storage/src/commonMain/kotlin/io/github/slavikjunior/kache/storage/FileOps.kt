package io.github.slavikjunior.kache.storage

/**
 * One directory entry, reduced to what the engine actually reads.
 *
 * Names are returned rather than paths so the engine never has to split a platform path:
 * it joins the root and the name itself, and every backend uses `/`, which both `java.io`
 * and kotlinx-io accept on every target.
 */
internal class FileEntry(
    val name: String,
    val isRegularFile: Boolean,
    val sizeBytes: Long,
)

/**
 * The slice of file-system work [FileStorageEngine] cannot do in `commonMain`.
 *
 * It exists as a seam rather than as `expect` functions per operation for two reasons. The
 * engine's logic — record layout, key encoding, eviction ranking, the temp-file protocol — is
 * the part that must behave identically everywhere, so it stays in one file; only these eight
 * calls differ. And a seam can be substituted in a test, which is how the "no temporary file
 * survives a failed write" case is provable without breaking the disk on purpose.
 *
 * `jvmCommonMain` implements it with `java.io` and `iosMain` with kotlinx-io, and that split is
 * deliberate: kotlinx-io's JVM file system is built on `java.nio.file`, which needs Android API
 * 26 while these modules declare `minSdk 23`, and core-library desugaring does not cover it.
 * One implementation per platform family is the price of keeping the Android floor.
 *
 * Implementations report failure by throwing; the engine maps anything that escapes into a
 * [io.github.slavikjunior.kache.core.KacheException] of its own.
 */
internal interface FileOps {

    /**
     * Creates [path] and any missing parent.
     *
     * @return True when [path] names a usable directory afterwards, false when it does not.
     *   A caller cannot act on the platform's specific error, and the engine only needs to
     *   decide whether to fail the construction, so a boolean is the whole contract.
     */
    fun createDirectories(path: String): Boolean

    /** True when [path] exists, as a file or a directory. */
    fun exists(path: String): Boolean

    /** Size of the file at [path] in bytes, or null when it does not exist. */
    fun sizeBytes(path: String): Long?

    /** Reads the whole file. The caller has already bounded its size through [sizeBytes]. */
    fun readBytes(path: String): ByteArray

    /**
     * Writes [bytes] to [target] so that a reader never observes a partial file.
     *
     * The implementation writes a temporary file inside [directory] — whose name starts with
     * [tempPrefix] and ends with [tempSuffix], so the engine can recognise and clean up a
     * leftover — and then moves it onto [target]. A fresh temporary name per call is what
     * keeps two concurrent writers of the same key from publishing each other's bytes.
     *
     * @throws IllegalStateException when the platform cannot move the file atomically. Writing
     *   non-atomically instead would trade a visible error for silent corruption.
     */
    fun writeAtomically(
        target: String,
        directory: String,
        bytes: ByteArray,
        tempPrefix: String,
        tempSuffix: String,
    )

    /** @return True when a file was actually removed. */
    fun delete(path: String): Boolean

    /** Entries of [directory], or an empty list when it does not exist. */
    fun list(directory: String): List<FileEntry>
}

/** The platform's [FileOps]; one instance is enough, so implementations are objects. */
internal expect fun platformFileOps(): FileOps

/**
 * Joins [directory] and [name], tolerating a trailing separator on either side.
 *
 * `/` is used on every target: `java.io.File` accepts it on Windows as well, and kotlinx-io's
 * `Path` normalises it.
 */
internal fun joinPath(directory: String, name: String): String = when {
    directory.isEmpty() -> name
    directory.endsWith('/') -> directory + name
    else -> "$directory/$name"
}
