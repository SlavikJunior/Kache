package io.github.slavikjunior.kache.sample.testutil

import io.github.slavikjunior.kache.core.EvictionStrategy
import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.core.StorageEngine
import io.github.slavikjunior.kache.core.StorageRecord
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer
import kotlinx.serialization.KSerializer
import kotlin.time.Duration.Companion.hours

/**
 * Minimal [StorageEngine] for tests: records live in a map.
 *
 * `removeExpired` and `touch` keep the interface defaults. The sample never configures a
 * `maxSize` or an auto-reap, and a fake that pretended to support them would make the
 * eviction path look tested when it is not.
 */
internal class InMemoryStorageEngine : StorageEngine {

    private val records = mutableMapOf<String, StorageRecord<*>>()

    /** Number of writes, so a test can prove what did and did not reach storage. */
    var writes: Int = 0
        private set

    override suspend fun get(key: String): StorageRecord<*>? = records[key]

    override suspend fun put(key: String, record: StorageRecord<*>) {
        records[key] = record
        writes++
    }

    override suspend fun remove(key: String): Boolean = records.remove(key) != null

    override suspend fun clear() {
        records.clear()
    }

    override suspend fun size(): Long = records.size.toLong()

    override suspend fun evictionCandidates(
        strategy: EvictionStrategy,
        limit: Int,
        now: Long,
    ): List<String> = records.keys.take(limit)
}

/**
 * An L2 cache over [InMemoryStorageEngine] with the production serializer.
 *
 * The real [KotlinxJsonSerializer] is used on purpose: it parses strictly, so a test that
 * round-trips domain models fails if one of them is not `@Serializable` or loses a field.
 */
internal fun <V : Any> testCache(serializer: KSerializer<V>): L2KmpCache<String, V> = L2KmpCache(
    storageEngine = InMemoryStorageEngine(),
    valueSerializer = KotlinxJsonSerializer(serializer),
    keyToString = { it },
    defaultTtl = 1.hours,
)
