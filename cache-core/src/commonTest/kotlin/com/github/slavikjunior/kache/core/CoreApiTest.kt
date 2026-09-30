package com.github.slavikjunior.kache.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CacheResultTest {
    @Test
    fun `get emits loading success and error states`() = runTest {
        val failure = KacheException.NetworkException(IllegalStateException("network unavailable"))
        val cache = StubCache(
            CacheResult.Loading("cached"),
            CacheResult.Success("fresh", CacheOrigin.NETWORK),
            CacheResult.Error(failure, "fallback"),
        )

        val states = cache.get("key", CacheStrategy.CacheFirst, null).toList()

        assertEquals(3, states.size)
        assertIs<CacheResult.Loading<String>>(states[0]).also {
            assertEquals("cached", it.cachedData)
        }
        assertIs<CacheResult.Success<String>>(states[1]).also {
            assertEquals("fresh", it.data)
            assertEquals(CacheOrigin.NETWORK, it.origin)
        }
        assertIs<CacheResult.Error<String>>(states[2]).also {
            assertSame(failure, it.error)
            assertEquals("fallback", it.cachedData)
        }
    }

    private class StubCache(
        private vararg val states: CacheResult<String>,
    ) : KmpCache<String, String> {
        override fun get(
            key: String,
            strategy: CacheStrategy,
            fetcher: (suspend (String) -> String)?,
        ): Flow<CacheResult<String>> = flow {
            states.forEach { emit(it) }
        }

        override suspend fun put(key: String, value: String, ttlMs: Long?) = Unit

        override suspend fun invalidate(key: String) = Unit

        override suspend fun clear() = Unit
    }
}

class KacheExceptionTest {
    @Test
    fun `all typed exceptions extend KacheException and preserve their data`() {
        val networkCause = IllegalStateException("network")
        val diskReadCause = IllegalArgumentException("read")
        val diskWriteCause = IllegalArgumentException("write")
        val serializationCause = IllegalArgumentException("serialize")
        val unknownCause = ArithmeticException("unknown")
        val key = "expired-key"

        val network = KacheException.NetworkException(networkCause)
        val diskRead = KacheException.DiskReadException(diskReadCause)
        val diskWrite = KacheException.DiskWriteException(diskWriteCause)
        val serialization = KacheException.SerializationException(serializationCause)
        val expired = KacheException.ExpiredException(key)
        val unknown = KacheException.UnknownKacheException(unknownCause)
        val cacheMiss = KacheException.CacheMissException("test miss")

        assertTrue(KacheException::class.isInstance(network))
        assertTrue(KacheException::class.isInstance(diskRead))
        assertTrue(KacheException::class.isInstance(diskWrite))
        assertTrue(KacheException::class.isInstance(serialization))
        assertTrue(KacheException::class.isInstance(expired))
        assertTrue(KacheException::class.isInstance(unknown))
        assertTrue(KacheException::class.isInstance(cacheMiss))

        assertSame(networkCause, network.cause)
        assertSame(diskReadCause, diskRead.cause)
        assertSame(diskWriteCause, diskWrite.cause)
        assertSame(serializationCause, serialization.cause)
        assertSame(unknownCause, unknown.cause)
        assertEquals(key, expired.key)
    }
}