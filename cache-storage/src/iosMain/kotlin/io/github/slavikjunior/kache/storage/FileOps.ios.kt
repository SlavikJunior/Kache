package io.github.slavikjunior.kache.storage

import kotlinx.io.buffered
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.random.Random

/**
 * [FileOps] on kotlinx-io, for the iOS targets.
 *
 * This is the only place in the module that needs a file-system API absent from the standard
 * library, and it is confined to iOS on purpose: kotlinx-io's JVM implementation is built on
 * `java.nio.file`, which needs Android API 26 while these modules declare `minSdk 23`. The
 * dependency stays `implementation`, and no kotlinx-io type reaches the public API — the
 * engine's constructor takes a `String` path.
 */
internal actual fun platformFileOps(): FileOps = KotlinxIoFileOps

private object KotlinxIoFileOps : FileOps {

    override fun createDirectories(path: String): Boolean {
        val directory = Path(path)
        return try {
            SystemFileSystem.createDirectories(directory)
            SystemFileSystem.metadataOrNull(directory)?.isDirectory == true
        } catch (e: Exception) {
            // Already a file, or a parent that cannot be created: report it instead of
            // leaking a platform exception the engine cannot interpret.
            SystemFileSystem.metadataOrNull(directory)?.isDirectory == true
        }
    }

    override fun exists(path: String): Boolean = SystemFileSystem.exists(Path(path))

    override fun sizeBytes(path: String): Long? {
        val metadata: FileMetadata = SystemFileSystem.metadataOrNull(Path(path)) ?: return null
        return if (metadata.isRegularFile) metadata.size else null
    }

    override fun readBytes(path: String): ByteArray =
        SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

    override fun writeAtomically(
        target: String,
        directory: String,
        bytes: ByteArray,
        tempPrefix: String,
        tempSuffix: String,
    ) {
        // kotlinx-io has no createTempFile equivalent, so the name has to be unique by
        // construction: two writers of the same key must never share a temporary file, or one
        // would publish the other's half-written bytes. A counter would need shared state and
        // a clock alone repeats within a millisecond, so randomness is the distinguishing part.
        val temp = Path(directory, tempPrefix + Random.nextLong().toULong().toString(16) + tempSuffix)
        try {
            SystemFileSystem.sink(temp).buffered().use { sink ->
                sink.write(bytes)
            }
            SystemFileSystem.atomicMove(temp, Path(target))
        } catch (e: Exception) {
            runCatching { SystemFileSystem.delete(temp, mustExist = false) }
            // Rewritten rather than rethrown as-is: `UnsupportedOperationException` from
            // atomicMove means the platform cannot publish this write safely, and the engine
            // maps everything that escapes here into a DiskWriteException.
            throw IllegalStateException("Cannot publish a record atomically", e)
        }
    }

    override fun delete(path: String): Boolean {
        val target = Path(path)
        if (!SystemFileSystem.exists(target)) return false
        return try {
            SystemFileSystem.delete(target, mustExist = false)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun list(directory: String): List<FileEntry> {
        val root = Path(directory)
        if (SystemFileSystem.metadataOrNull(root)?.isDirectory != true) return emptyList()

        return try {
            SystemFileSystem.list(root).mapNotNull { child ->
                val metadata = SystemFileSystem.metadataOrNull(child) ?: return@mapNotNull null
                FileEntry(
                    name = child.name,
                    isRegularFile = metadata.isRegularFile,
                    sizeBytes = metadata.size,
                )
            }
        } catch (e: Exception) {
            // A directory that disappeared between the check and the listing is empty, not an
            // error: the engine reports counts, and a failed count is not worth a crash.
            emptyList()
        }
    }
}
