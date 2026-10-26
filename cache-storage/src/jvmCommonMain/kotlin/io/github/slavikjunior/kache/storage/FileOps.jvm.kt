package io.github.slavikjunior.kache.storage

import java.io.File
import java.io.IOException

/**
 * [FileOps] on `java.io`, shared by the JVM and Android targets.
 *
 * `java.io` rather than `java.nio.file` on purpose: `Files`, `Path` and `StandardCopyOption`
 * arrived in Android API 26 while these modules declare `minSdk 23`, and core-library
 * desugaring does not cover `java.nio.file`. kotlinx-io's JVM file system is built on
 * `java.nio.file` too, which is why the iOS implementation uses it and this one does not.
 */
internal actual fun platformFileOps(): FileOps = JavaFileOps

private object JavaFileOps : FileOps {

    override fun createDirectories(path: String): Boolean {
        val directory = File(path)
        return directory.isDirectory || directory.mkdirs()
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun sizeBytes(path: String): Long? {
        val file = File(path)
        return if (file.isFile) file.length() else null
    }

    override fun readBytes(path: String): ByteArray = File(path).readBytes()

    override fun writeAtomically(
        target: String,
        directory: String,
        bytes: ByteArray,
        tempPrefix: String,
        tempSuffix: String,
    ) {
        var temp: File? = null
        try {
            // A fresh temporary file per write. A fixed name derived from the target would
            // be shared by concurrent writers of the same key, and one of them would move
            // the other's partially written bytes into place.
            temp = File.createTempFile(tempPrefix, tempSuffix, File(directory))
            temp.writeBytes(bytes)
            moveIntoPlace(temp, File(target))
        } catch (e: IOException) {
            temp?.delete()
            throw e
        }
    }

    override fun delete(path: String): Boolean = File(path).delete()

    override fun list(directory: String): List<FileEntry> =
        File(directory).listFiles()?.map { file ->
            FileEntry(name = file.name, isRegularFile = file.isFile, sizeBytes = file.length())
        }.orEmpty()

    /**
     * Moves [source] onto [target], replacing whatever was there.
     *
     * On Linux and Android `File.renameTo` is `rename(2)`: atomic, and it replaces an existing
     * file. Neither is guaranteed on every platform, so a failure falls back to a copy, which
     * is not atomic and is therefore only reached where the atomic move is unavailable.
     */
    private fun moveIntoPlace(source: File, target: File) {
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = true)
        source.delete()
    }
}
