package io.github.slavikjunior.kache.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contention tests for [L1MemoryCache]'s mutex.
 *
 * These dispatch to [Dispatchers.Default] on purpose. Everything else in `commonTest`
 * runs on the single-threaded scheduler of `runTest`, which interleaves coroutines only
 * at suspension points and therefore never has two of them inside the critical section at
 * once. A race would pass there and fail in production, so these cases must genuinely
 * run in parallel.
 */
class L1MemoryCacheConcurrencyTest {

    @Test
    fun concurrentWritesToDistinctKeysAllLand() = runBlocking {
        val cache = L1MemoryCache<Int, Int>(maxSize = KEYS)

        withContext(Dispatchers.Default) {
            List(KEYS) { key ->
                async { cache.put(key, key * 2) }
            }.awaitAll()
        }

        assertEquals(KEYS, cache.size())
        for (key in 0 until KEYS) {
            assertEquals(key * 2, cache.get(key)?.value, "key $key")
        }
    }

    @Test
    fun concurrentWritesToTheSameKeyLeaveOneConsistentValue() = runBlocking {
        val cache = L1MemoryCache<Int, Int>(maxSize = 1)
        val written = (0 until WRITERS).map { writer ->
            async(Dispatchers.Default) {
                repeat(WRITES_PER_WRITER) { cache.put(WRITER_KEY, writer) }
            }
        }
        written.awaitAll()

        assertEquals(1, cache.size())
        val value = cache.get(WRITER_KEY)?.value
        assertTrue(value != null && value in 0 until WRITERS, "got $value")
    }

    @Test
    fun concurrentReadsAndWritesDoNotCorruptTheAccessOrder() = runBlocking {
        // Capacity 1 makes every write evict, so the access-order bookkeeping is
        // exercised on every iteration rather than once.
        val cache = L1MemoryCache<Int, Int>(maxSize = 1)

        withContext(Dispatchers.Default) {
            val jobs = (0 until KEYS).map { key ->
                async {
                    cache.put(key, key)
                    cache.get(key)
                }
            }
            jobs.awaitAll()
        }

        assertEquals(1, cache.size(), "capacity must always be respected")
    }

    @Test
    fun clearRacingWithWritesLeavesAConsistentSize() = runBlocking {
        val cache = L1MemoryCache<Int, Int>(maxSize = KEYS)

        withContext(Dispatchers.Default) {
            val writer = launch {
                repeat(ROUNDS) { round -> cache.put(round % KEYS, round) }
            }
            val cleaner = launch {
                repeat(ROUNDS) { cache.clear() }
            }
            writer.join()
            cleaner.join()
        }

        // Whatever the interleaving, the cache must never report more than its capacity.
        assertTrue(cache.size() <= KEYS, "size was ${cache.size()}")
    }

    @Test
    fun removeExpiredUnderContentionKeepsTheCacheWithinCapacity() = runBlocking {
        val time = MutableTimeSource(initialTimeMillis = 0L)
        val cache = L1MemoryCache<Int, Int>(maxSize = KEYS, timeSource = time)

        withContext(Dispatchers.Default) {
            val jobs = (0 until KEYS).map { key ->
                async { cache.put(key, key, ttlMs = 10L) }
            }
            jobs.awaitAll()
            time.advance(100L)
        }

        val removed = cache.removeExpired()

        assertEquals(KEYS, removed)
        assertEquals(0, cache.size())
    }

    private companion object {
        const val KEYS = 200
        const val WRITERS = 8
        const val WRITES_PER_WRITER = 100
        const val WRITER_KEY = 42
        const val ROUNDS = 200
    }
}