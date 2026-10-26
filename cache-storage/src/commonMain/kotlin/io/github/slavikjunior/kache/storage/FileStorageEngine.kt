package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.EvictionStrategy
import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.StorageEngine
import io.github.slavikjunior.kache.core.StorageRecord
import io.github.slavikjunior.kache.core.recordComparator

/** 16 MiB. Generous for a cache value, small enough that a corrupt file cannot OOM. */
internal const val DEFAULT_MAX_RECORD_BYTES: Long = 16L * 1024 * 1024

/**
 * [StorageEngine] backed by the local file system. One file per key.
 *
 * The algorithm lives in `commonMain` so every platform shares it: record layout, key
 * encoding, eviction ranking and the temp-file protocol are the parts that must behave
 * identically, and a second copy per target would drift. Files themselves are touched
 * through [FileOps] — `java.io` on JVM and Android, kotlinx-io on iOS — because kotlinx-io's
 * JVM file system needs Android API 26 and this module supports 23.
 *
 * Writes are made atomic: the payload goes to a temporary file which then replaces the real
 * one, so a process death mid-write cannot leave a half-written record behind. Each write
 * gets its own temporary file, so two writers racing on the same key cannot publish each
 * other's half-written bytes.
 *
 * Expired records are returned rather than deleted by [get]. Whether an expired value is
 * still useful is a decision for the caching strategy above, so that a strategy such as
 * `StaleWhileRevalidate` can still read a stale value. Reaping is therefore explicit,
 * through [removeExpired] or [clear].
 *
 * @param rootDirectory Directory holding the record files. Created if missing.
 * @param maxRecordBytes Largest record this engine will read or write. A cache payload is
 *   attacker-influenced data read from disk, so an unbounded read is an unbounded
 *   allocation; a file over this size is treated as corrupt rather than loaded. Defaults to
 *   16 MiB.
 */
public class FileStorageEngine internal constructor(
    rootDirectory: String,
    private val maxRecordBytes: Long,
    private val files: FileOps,
) : StorageEngine {

    /**
     * @param rootDirectory Directory holding the record files. Created if missing.
     * @param maxRecordBytes Largest record this engine will read or write.
     */
    public constructor(
        rootDirectory: String,
        maxRecordBytes: Long = DEFAULT_MAX_RECORD_BYTES,
    ) : this(rootDirectory, maxRecordBytes, platformFileOps())

    /** Root without a trailing separator, so [joinPath] cannot produce a doubled one. */
    private val root: String = rootDirectory.trimEnd('/')

    init {
        require(maxRecordBytes > 0) { "maxRecordBytes must be positive, but was $maxRecordBytes" }

        if (!files.createDirectories(root)) {
            throw KacheException.DiskWriteException(
                IllegalArgumentException("Cannot create cache directory: $rootDirectory")
            )
        }
    }

    override suspend fun get(key: String): StorageRecord<*>? {
        val path = fileFor(key)
        if (!files.exists(path)) return null

        return try {
            val bytes = readEncoded(path)
            // Over the size limit: not a record this engine is willing to load. Dropped
            // like any other unreadable file so the key can be repopulated.
            if (bytes == null) {
                files.delete(path)
                null
            } else {
                StorageRecordFileCodec.decode(bytes)
            }
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            // Unreadable or corrupt: drop it so the key can be repopulated on the next write.
            files.delete(path)
            null
        }
    }

    override suspend fun put(key: String, record: StorageRecord<*>) {
        val encoded = StorageRecordFileCodec.encode(record)
        if (encoded.size > maxRecordBytes) {
            // The key is deliberately absent from the message: keys are caller data and
            // often user-derived, and this message ends up in logs and crash reports.
            throw KacheException.DiskWriteException(
                IllegalArgumentException(
                    "Record of ${encoded.size} bytes exceeds the $maxRecordBytes byte limit"
                )
            )
        }

        try {
            files.writeAtomically(
                target = fileFor(key),
                directory = root,
                bytes = encoded,
                tempPrefix = TEMP_PREFIX,
                tempSuffix = TEMP_SUFFIX,
            )
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(e)
        }
    }

    override suspend fun remove(key: String): Boolean = files.delete(fileFor(key))

    override suspend fun clear() {
        files.list(root).forEach { files.delete(joinPath(root, it.name)) }
    }

    /**
     * Number of records this engine wrote, as a file count.
     *
     * Temporary files and names [keyOf] cannot have produced are excluded: a stray file in the
     * directory is not a record, and counting it would make capacity management evict a live
     * record early. Counting names rather than decoding every payload keeps the call cheap
     * enough to run on every write.
     */
    override suspend fun size(): Long =
        files.list(root).count { it.isRegularFile && !it.name.endsWith(TEMP_SUFFIX) && keyOf(it.name) != null }
            .toLong()

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
        var removed = 0L

        files.list(root).forEach { entry ->
            if (!entry.isRegularFile || entry.name.endsWith(TEMP_SUFFIX)) return@forEach

            val path = joinPath(root, entry.name)
            val expired = try {
                val bytes = readEncoded(path)
                bytes == null || StorageRecordFileCodec.decode(bytes).isExpired(now)
            } catch (e: KacheException) {
                throw e
            } catch (e: Exception) {
                true
            }

            if (expired && files.delete(path)) {
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
        val path = fileFor(key)
        if (!files.exists(path)) return false

        val record = try {
            val bytes = readEncoded(path)
            if (bytes == null) {
                files.delete(path)
                return false
            }
            StorageRecordFileCodec.decode(bytes)
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            files.delete(path)
            return false
        }

        put(key, record.copy(lastAccessedAt = accessedAt))
        return true
    }

    /**
     * Ranks the stored records by [strategy], expired ones first, and returns their keys.
     *
     * The keys are recovered from the file names, which encode the key reversibly; see
     * [encodeKey]. A name that does not decode back to itself is not a record this engine
     * wrote and is skipped.
     *
     * Only the `limit` worst records are ever held at once: candidates are pushed through a
     * bounded structure, so ranking a directory of a million records costs memory proportional
     * to `limit`, not to the record count. Reading every header is still unavoidable — there
     * is no index to order by when the backend is one file per key — but the sort is not.
     */
    override suspend fun evictionCandidates(
        strategy: EvictionStrategy,
        limit: Int,
        now: Long,
    ): List<String> {
        if (limit <= 0) return emptyList()

        val byStrategy = strategy.recordComparator()
        // Expired records first: none of them can be served again, so dropping one costs
        // nothing regardless of what the strategy would have picked.
        val evictionOrder = Comparator<Candidate> { left, right ->
            val expired = right.record.isExpired(now).compareTo(left.record.isExpired(now))
            if (expired != 0) expired else byStrategy.compare(left.record, right.record)
        }

        // A max-heap under the eviction order: its root is the *least* evictable candidate
        // held, so an overflow replaces exactly the one we want to keep least.
        val worstKept = WorstCandidates(limit, evictionOrder)

        files.list(root).forEach { entry ->
            if (!entry.isRegularFile || entry.name.endsWith(TEMP_SUFFIX)) return@forEach

            val key = keyOf(entry.name) ?: return@forEach
            val record = try {
                val bytes = readEncoded(joinPath(root, entry.name)) ?: return@forEach
                StorageRecordFileCodec.decode(bytes)
            } catch (e: KacheException) {
                throw e
            } catch (e: Exception) {
                return@forEach
            }

            worstKept.add(Candidate(record, key))
        }

        return worstKept.sorted().map { it.key }
    }

    /** A record paired with the key it is stored under. */
    private class Candidate(val record: StorageRecord<ByteArray>, val key: String)

    /**
     * Keeps the [limit] most evictable candidates, dropping the least evictable on overflow.
     *
     * A binary heap written here rather than taken from a library: `java.util.PriorityQueue`
     * does not exist on Kotlin/Native, and an insertion-sorted list would turn ranking a large
     * directory into `O(n · limit)`. The comparator is the eviction order, so the root is
     * always the entry the strategy would keep longest — the one an overflow must discard.
     */
    private class WorstCandidates(
        private val limit: Int,
        private val order: Comparator<Candidate>,
    ) {
        private val heap = ArrayList<Candidate>(limit + 1)

        fun add(candidate: Candidate) {
            if (heap.size < limit) {
                heap.add(candidate)
                siftUp(heap.lastIndex)
            } else if (order.compare(candidate, heap[0]) < 0) {
                // More evictable than the worst kept so far: it takes that slot.
                heap[0] = candidate
                siftDown(0)
            }
        }

        fun sorted(): List<Candidate> = heap.sortedWith(order)

        private fun siftUp(from: Int) {
            var child = from
            while (child > 0) {
                val parent = (child - 1) / 2
                if (order.compare(heap[parent], heap[child]) >= 0) return
                heap.swap(parent, child)
                child = parent
            }
        }

        private fun siftDown(from: Int) {
            var parent = from
            while (true) {
                val left = parent * 2 + 1
                if (left >= heap.size) return
                val right = left + 1
                val larger = if (right < heap.size && order.compare(heap[right], heap[left]) > 0) right else left
                if (order.compare(heap[parent], heap[larger]) >= 0) return
                heap.swap(parent, larger)
                parent = larger
            }
        }

        private fun ArrayList<Candidate>.swap(first: Int, second: Int) {
            val value = this[first]
            this[first] = this[second]
            this[second] = value
        }
    }

    private fun fileFor(key: String): String = joinPath(root, encodeKey(key))

    /**
     * Reads a record file, refusing to load one over [maxRecordBytes].
     *
     * @return The bytes, or null when the file is too large to be a record this engine wrote.
     */
    private fun readEncoded(path: String): ByteArray? {
        val size = files.sizeBytes(path) ?: return null
        if (size > maxRecordBytes) return null
        return files.readBytes(path)
    }

    private companion object {
        private const val TEMP_SUFFIX = ".tmp"
        private const val TEMP_PREFIX = "kache-"

        /**
         * Leading marker on every file name this engine writes.
         *
         * It is what makes path traversal impossible: without it a key of `..` would map
         * onto the parent directory, and joining the root with `..` names a directory above
         * the cache root. With the marker the name is always `k..`, which is an ordinary file.
         */
        private const val FILE_NAME_MARKER = "k"

        private const val ESCAPE = '%'
        private const val HEX_DIGITS = "0123456789ABCDEF"

        /**
         * Maps a key onto a file name, reversibly.
         *
         * Characters outside the unreserved set are percent-encoded, so two distinct keys can
         * never share a file. That is the property the previous lossy sanitisation lacked:
         * `"user:1"` and `"user_1"` both collapsed onto `user_1`, so writing one silently
         * replaced the other and reading either returned the other's value — a cross-key data
         * leak in any key scheme built from user data.
         *
         * `%` is itself encoded, which is what keeps the mapping injective rather than merely
         * "usually distinct".
         *
         * The result always starts with [FILE_NAME_MARKER] and contains no path separator, so
         * it can only ever name a file directly inside the cache directory.
         */
        private fun encodeKey(key: String): String = buildString(key.length + FILE_NAME_MARKER.length) {
            append(FILE_NAME_MARKER)
            key.forEach { ch ->
                if (ch.isUnreserved()) {
                    append(ch)
                } else {
                    append(ESCAPE)
                    append(HEX_DIGITS[(ch.code shr 4) and 0xF])
                    append(HEX_DIGITS[ch.code and 0xF])
                }
            }
        }

        /**
         * Recovers the key from a file name produced by [encodeKey].
         *
         * @return The key, or null if the name was not produced by [encodeKey]. The round trip
         *   is verified rather than assumed, so a name that decodes to something that would
         *   encode differently is rejected: callers hand these keys back to [remove], and a key
         *   that does not resolve to the same file would silently delete nothing.
         */
        private fun keyOf(fileName: String): String? {
            if (!fileName.startsWith(FILE_NAME_MARKER)) return null

            val body = fileName.substring(FILE_NAME_MARKER.length)
            val key = buildString(body.length) {
                var i = 0
                while (i < body.length) {
                    val ch = body[i]
                    if (ch == ESCAPE && i + 2 < body.length) {
                        val high = hexValue(body[i + 1])
                        val low = hexValue(body[i + 2])
                        if (high >= 0 && low >= 0) {
                            append(((high shl 4) or low).toChar())
                            i += 3
                            continue
                        }
                    }
                    append(ch)
                    i++
                }
            }

            return key.takeIf { encodeKey(it) == fileName }
        }

        /** Characters that need no escaping and cannot name a directory on their own. */
        private fun Char.isUnreserved(): Boolean =
            this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
                this == '-' || this == '_' || this == '.'

        private fun hexValue(ch: Char): Int = when (ch) {
            in '0'..'9' -> ch - '0'
            in 'a'..'f' -> ch - 'a' + 10
            in 'A'..'F' -> ch - 'A' + 10
            else -> -1
        }
    }
}
