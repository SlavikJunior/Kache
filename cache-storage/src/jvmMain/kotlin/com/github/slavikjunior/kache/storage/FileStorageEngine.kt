package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.KacheException
import com.github.slavikjunior.kache.core.StorageRecord
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [StorageEngine] backed by the local file system. One file per key.
 *
 * Writes are made atomic: the payload goes to a temporary file which then replaces the
 * real one, so a process death mid-write cannot leave a half-written record behind.
 *
 * Expired records are returned rather than deleted. Whether an expired value is still
 * useful is a decision for the caching strategy above, and reaping is left to
 * [clear] or an explicit [removeExpired] call, so that a strategy such as
 * `StaleWhileRevalidate` can still read a stale value.
 *
 * @param rootDirectory Directory holding the record files. Created if missing.
 */
public class FileStorageEngine(rootDirectory: String) : com.github.slavikjunior.kache.core.StorageEngine {

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
