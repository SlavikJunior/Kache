package io.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days
import kotlin.test.assertNotNull

/**
 * Compile-only guard for the README snippets.
 *
 * The documentation claims these calls exist with these signatures. If one of them drifts,
 * this file stops compiling, which is a far cheaper signal than a reader discovering it.
 * Nothing here is executed.
 */
@Suppress("unused", "UNUSED_VARIABLE")
class ReadmeSnippetCheck {

    private val api = object {
        suspend fun loadUser(id: String): String = id
    }
    private val repository = object {
        suspend fun load(id: String): String = id
    }

    private fun cacheFlow(cache: KmpCache<String, String>): Flow<CacheResult<String>> =
        cache.get(
            key = "user-42",
            strategy = CacheStrategy.CacheFirst,
            fetcher = { api.loadUser("42") },
        )

    private suspend fun recipe1(): Any {
        val cache = L1MemoryCache<String, String>(maxSize = 100, defaultTtl = 60.seconds)
        return listOf(cache.put("greeting", "hello"), cache.get("greeting"), cache.size(), cache.removeExpired())
    }

    private fun recipe4(engine: StorageEngine): Any {
        return ChainKmpCache<String, String>(
            l1Cache = L1MemoryCache(maxSize = 200, defaultTtl = 5.minutes),
            l2Storage = engine,
            serializer = StringSerializer(),
            defaultTtl = 1.days,
        )
    }

    private fun recipe5(engine: StorageEngine): Any = L2KmpCache<StringBuilder, String>(
        storageEngine = engine,
        valueSerializer = StringSerializer(),
        keyToString = { it.toString() },
        defaultTtl = 1.hours,
    )

    private fun recipe9(): Any = RetryPolicy.Exponential(
        maxAttempts = 3,
        initialDelay = 200.milliseconds,
        maxDelay = 2.seconds,
        multiplier = 2.0,
        jitterRatio = 0.2,
    )

    private fun recipe9Shortcuts(): Any = listOf(
        RetryPolicy.aggressive(),
        RetryPolicy.fixed(3, 200.milliseconds),
        RetryPolicy.exponential(),
        RetryPolicy.None,
    )

    private suspend fun recipe11(): Any? {
        val clock = MutableTimeSource(initialTimeMillis = 0L)
        val cache = L1MemoryCache<String, String>(maxSize = 10, timeSource = clock)
        cache.put("k", "v", ttl = 100.milliseconds)
        clock.advance(101.milliseconds)
        val afterExpiry: StorageRecord<String>? = cache.get("k")
        return afterExpiry
    }

    /** The bounded-L2 recipe, including the zero-granularity variant called out under it. */
    private fun recipe10(engine: StorageEngine): Any {
        val cache = L2KmpCache<String, String>(
            storageEngine = engine,
            valueSerializer = TextLikeSerializer(),
            maxSize = 500,
            evictionStrategy = EvictionStrategy.LRU,
            autoReapEvery = 10.minutes,
        )
        val exact = L2KmpCache<String, String>(
            storageEngine = engine,
            valueSerializer = TextLikeSerializer(),
            maxSize = 100,
            touchGranularity = Duration.ZERO,
        )
        return listOf(cache.stopAutoReap(), exact.stopAutoReap())
    }

    /** The chain cache takes the same housekeeping parameters and reaps L1 as well. */
    private fun recipe10Chain(l1: L1MemoryCache<String, String>, l2: StorageEngine): ChainKmpCache<String, String> =
        ChainKmpCache(
            l1Cache = l1,
            l2Storage = l2,
            serializer = TextLikeSerializer(),
            maxSize = 100,
            evictionStrategy = EvictionStrategy.FIFO,
            autoReapEvery = 10.minutes,
        )

    private class TextLikeSerializer : KacheSerializer<String> {
        override fun serialize(value: String): ByteArray = value.encodeToByteArray()
        override fun deserialize(bytes: ByteArray): String = bytes.decodeToString()
    }

    @Test
    fun theSnippetsReferToRealApi() {
        // Deliberately does nothing: the value of this file is that it compiles.
        assertNotNull(RetryPolicy.None)
    }
}
