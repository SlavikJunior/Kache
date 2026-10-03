package io.github.slavikjunior.kache.android

import io.github.slavikjunior.kache.core.KacheSerializer
import io.github.slavikjunior.kache.core.StorageEngine
import io.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.robolectric.RuntimeEnvironment
import java.io.File

/** UTF-8 text serializer, so the tests do not need a serialization library. */
internal object TextSerializer : KacheSerializer<String> {
    override fun serialize(value: String): ByteArray = value.encodeToByteArray()
    override fun deserialize(bytes: ByteArray): String = bytes.decodeToString()
}

/**
 * Recording [StorageEngine] used to assert what the KTX layer does and does not touch.
 *
 * Every call is counted, so a test can prove that an eviction path really reached the
 * engine instead of only clearing something the cache kept in memory.
 */
internal class RecordingStorageEngine : StorageEngine {

    private val records = mutableMapOf<String, StorageRecord<*>>()
    private val mutex = Mutex()

    var clearCount = 0
        private set
    var putCount = 0
        private set
    var sizeCalls = 0
        private set

    override suspend fun get(key: String): StorageRecord<*>? = mutex.withLock { records[key] }

    override suspend fun put(key: String, record: StorageRecord<*>) {
        mutex.withLock {
            records[key] = record
            putCount++
        }
    }

    override suspend fun remove(key: String): Boolean = mutex.withLock { records.remove(key) != null }

    override suspend fun clear() {
        mutex.withLock {
            records.clear()
            clearCount++
        }
    }

    override suspend fun size(): Long {
        sizeCalls++
        return mutex.withLock { records.size.toLong() }
    }
}

/** Root of an app-private cache directory for a test, removed afterwards. */
internal fun temporaryCacheDirectory(name: String): File {
    val context = RuntimeEnvironment.getApplication()
    val dir = File(context.cacheDir, name)
    dir.deleteRecursively()
    return dir
}