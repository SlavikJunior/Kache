package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.EvictionStrategy
import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.StorageRecord
import io.github.slavikjunior.kache.core.recordComparator
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

    /**
     * Rewrites the record header so its access timestamp reads [accessedAt].
     *
     * Only the header changes, but the payload is rewritten with it because the format is
     * a single contiguous block. Callers are expected to invoke this at most once per touch
     * window per key, which is what keeps a hot key from costing a write per read.
     */
    override suspend fun touch(key: String, accessedAt: Long): Boolean {
        val file = fileFor(key)
        if (!file.exists()) return false

        val record = try {
            StorageRecordFileCodec.decode(Files.readAllBytes(file.toPath()))
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            file.delete()
            return false
        }

        put(key, record.copy(lastAccessedAt = accessedAt))
        return true
    }

    /**
     * Ranks the record files by [strategy], expired ones first.
     *
     * Ranking reads every header, so this is O(n) in the record count: there is no index to
     * order by in a backend that keeps one file per key. Callers should therefore ask only
     * for the handful of keys they are about to evict, not for a full ranking.
     *
     * The returned names are file names, not the original keys. File names are produced by
     * a lossy sanitisation, so the original key cannot be recovered from disk. They remain
     * valid inputs to [remove], because sanitisation is idempotent: it leaves an already
     * safe name untouched.
     *
     * Files that fail to decode are skipped rather than ranked, which is the same treatment
     * [get] gives them; [removeExpired] is what actually deletes them.
     */
    override suspend fun evictionCandidates(
        strategy: EvictionStrategy,
        limit: Int,
        now: Long,
    ): List<String> {
        if (limit <= 0) return emptyList()

        val byStrategy = strategy.recordComparator()
        val comparator = Comparator<Pair<StorageRecord<*>, String>> { left, right ->
            byStrategy.compare(left.first, right.first)
        }

        return rootDir.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) }
            .mapNotNull { file ->
                val record = try {
                    StorageRecordFileCodec.decode(Files.readAllBytes(file.toPath()))
                } catch (e: KacheException) {
                    throw e
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                record to file.name
            }
            .sortedWith(
                // Expired records first: none of them can be served again, so dropping one
                // costs nothing regardless of what the strategy would have picked.
                compareByDescending<Pair<StorageRecord<*>, String>> { it.first.isExpired(now) }
                    .then(comparator),
            )
            .take(limit)
            .map { (_, name) -> name }
    }

    private fun fileFor(key: String): File = File(rootDir, sanitizeKey(key))

    private companion object {
        private const val TEMP_SUFFIX = ".tmp"

        private val UNSAFE_CHARS = Regex("[^a-zA-Z0-9._-]")

        /**
         * Maps an arbitrary key onto a safe file name.
         *
         * Distinct keys can collapse onto the same file, so callers that need a
         * collision-free mapping should supply their own key encoding. The mapping is
         * idempotent, which is what lets [evictionCandidates] hand file names back to
         * callers as if they were keys.
         */
        private fun sanitizeKey(key: String): String = key.replace(UNSAFE_CHARS, "_")
    }
}
