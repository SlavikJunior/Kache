package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.StorageRecord
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [StorageEngine] backed by the local file system. One file per key.
 *
 * Shared by the JVM and Android targets through the `jvmCommonMain` source set: both use
 * `java.io`, so the implementation is identical and was previously duplicated per target.
 *
 * Writes are made atomic: the payload goes to a temporary file which then replaces the
 * real one, so a process death mid-write cannot leave a half-written record behind.
 *
 * Expired records are returned rather than deleted by [get]. Whether an expired value is
 * still useful is a decision for the caching strategy above, so that a strategy such as
 * `StaleWhileRevalidate` can still read a stale value. Reaping is therefore explicit,
 * through [removeExpired] or [clear].
 *
 * @param rootDirectory Directory holding the record files. Created if missing.
 */
public class FileStorageEngine(rootDirectory: String) : io.github.slavikjunior.kache.core.StorageEngine {

    private val rootDir: File = File(rootDirectory)

    init {
        if (!rootDir.exists() && !rootDir.mkdirs() && !rootDir.isDirectory) {
            throw KacheException.DiskWriteException(
                IOException("Cannot create cache directory: $rootDirectory")
            )
        }
    }

    override suspend fun get(key: String): StorageRecord<*>? {
        val file = fileFor(key)
        if (!file.exists()) return null

        return try {
            StorageRecordFileCodec.decode(Files.readAllBytes(file.toPath()))
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            // Unreadable or corrupt: drop it so the key can be repopulated on the next write.
            file.delete()
            null
        }
    }

    override suspend fun put(key: String, record: StorageRecord<*>) {
        val target = fileFor(key)
        val temp = File(rootDir, "${target.name}$TEMP_SUFFIX")

        try {
            Files.write(temp.toPath(), StorageRecordFileCodec.encode(record))
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: IOException) {
            temp.delete()
            throw KacheException.DiskWriteException(e)
        }
    }

    override suspend fun remove(key: String): Boolean = fileFor(key).delete()

    override suspend fun clear() {
        rootDir.listFiles()?.forEach { it.delete() }
    }

    override suspend fun size(): Long =
        rootDir.listFiles()?.count { it.isFile && !it.name.endsWith(TEMP_SUFFIX) }?.toLong() ?: 0L

    /**
     * Deletes every expired record and reports how many went.
     *
     * Implemented here rather than left on the interface default, which returns 0: a
     * backend that silently reaps nothing would grow without bound, because expired
     * records are only otherwise removed when a key is read or overwritten.
     *
     * A record that cannot be decoded is counted as removed. `get` already treats it as
     * absent and deletes it, so keeping the file would only make the size report wrong.
     */
    override suspend fun removeExpired(now: Long): Long {
        val files = rootDir.listFiles() ?: return 0L
        var removed = 0L

        files.forEach { file ->
            if (!file.isFile || file.name.endsWith(TEMP_SUFFIX)) return@forEach

            val expired = try {
                StorageRecordFileCodec.decode(Files.readAllBytes(file.toPath())).isExpired(now)
            } catch (e: KacheException) {
                throw e
            } catch (e: Exception) {
                true
            }

            if (expired && file.delete()) {
                removed++
            }
        }

        return removed
    }

    private fun fileFor(key: String): File = File(rootDir, sanitizeKey(key))

    private companion object {
        private const val TEMP_SUFFIX = ".tmp"

        /**
         * Maps an arbitrary key onto a safe file name.
         *
         * Distinct keys can collapse onto the same file, so callers that need a
         * collision-free mapping should supply their own [keyToString] encoding.
         */
        private fun sanitizeKey(key: String): String = key.replace(UNSAFE_CHARS, "_")

        private val UNSAFE_CHARS = Regex("[^a-zA-Z0-9._-]")
    }
}
