package io.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
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

    private suspend fun recipe10(): Any? {
        val clock = MutableTimeSource(initialTimeMillis = 0L)
        val cache = L1MemoryCache<String, String>(maxSize = 10, timeSource = clock)
        cache.put("k", "v", ttl = 100.milliseconds)
        clock.advance(101.milliseconds)
        val afterExpiry: StorageRecord<String>? = cache.get("k")
        return afterExpiry
    }

    @Test
    fun theSnippetsReferToRealApi() {
        // Deliberately does nothing: the value of this file is that it compiles.
        assertNotNull(RetryPolicy.None)
    }
}
