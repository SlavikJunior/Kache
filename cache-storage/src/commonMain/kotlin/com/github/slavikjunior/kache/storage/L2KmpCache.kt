package com.github.slavikjunior.kache.storage

import com.github.slavikjunior.kache.core.CacheOrigin
import com.github.slavikjunior.kache.core.CacheResult
import com.github.slavikjunior.kache.core.CacheStrategy
import com.github.slavikjunior.kache.core.KacheException
import com.github.slavikjunior.kache.core.KacheSerializer
import com.github.slavikjunior.kache.core.KmpCache
import com.github.slavikjunior.kache.core.StorageEngine
import com.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock

/**
 * A single-tier cache built directly on a [StorageEngine], with no in-memory tier.
 *
 * Use this when only durable storage is wanted. When an in-memory tier should sit in
 * front of the backend, use `ChainKmpCache` from the core module instead, which already
 * implements the strategies below over L1 plus L2.
 *
 * @param K Key type. Must be convertible to a stable string via [keyToString].
 * @param V Value type.
 * @param storageEngine Persistent backend holding the records.
 * @param valueSerializer Serializer used to encode values on write and decode them on read.
 * @param keyToString Maps a typed key to the string key used by [storageEngine]. Override
 *   when the default [Any.toString] is not stable or safe as a storage key.
 */
public class L2KmpCache<K : Any, V : Any>(
    private val storageEngine: StorageEngine,
    private val valueSerializer: KacheSerializer<V>,
    private val keyToString: (K) -> String = { it.toString() },
) : KmpCache<K, V> {

    override fun get(
        key: K,
        strategy: CacheStrategy,
        fetcher: (suspend (K) -> V)?,
    ): Flow<CacheResult<V>> = when (strategy) {
        CacheStrategy.CacheFirst -> cacheFirst(key, fetcher)
        CacheStrategy.NetworkFirst -> networkFirst(key, fetcher)
        CacheStrategy.CacheAndNetwork -> cacheAndNetwork(key, fetcher)
        CacheStrategy.StaleWhileRevalidate -> staleWhileRevalidate(key, fetcher)
    }

    private fun cacheFirst(key: K, fetcher: (suspend (K) -> V)?): Flow<CacheResult<V>> = flow {
        readCache(key, allowStale = false)?.let { cached ->
            emit(CacheResult.Success(cached.value, cached.origin))
            return@flow
        }

        when (val outcome = fetch(key, fetcher)) {
            is FetchOutcome.Success -> {
                write(key, outcome.value)
                emit(CacheResult.Success(outcome.value, CacheOrigin.NETWORK))
            }
            is FetchOutcome.Failure -> emit(CacheResult.Error(KacheException.NetworkException(outcome.cause)))
            FetchOutcome.Absent -> emit(CacheResult.Error(KacheException.CacheMissException(NO_FETCHER_MESSAGE)))
        }
    }

    private fun networkFirst(key: K, fetcher: (suspend (K) -> V)?): Flow<CacheResult<V>> = flow {
        when (val outcome = fetch(key, fetcher)) {
            is FetchOutcome.Success -> {
                write(key, outcome.value)
                emit(CacheResult.Success(outcome.value, CacheOrigin.NETWORK))
                return@flow
            }
            is FetchOutcome.Failure, FetchOutcome.Absent -> Unit
        }

        readCache(key, allowStale = false)?.let { cached ->
            emit(CacheResult.Success(cached.value, cached.origin))
            return@flow
        }

        emit(CacheResult.Error(KacheException.CacheMissException(NO_DATA_AFTER_FETCH_MESSAGE)))
    }

    private fun cacheAndNetwork(key: K, fetcher: (suspend (K) -> V)?): Flow<CacheResult<V>> = flow {
        val cached = readCache(key, allowStale = false)
        if (cached != null) {
            emit(CacheResult.Success(cached.value, cached.origin))
        }

        when (val outcome = fetch(key, fetcher)) {
            is FetchOutcome.Success -> {
                write(key, outcome.value)
                emit(CacheResult.Success(outcome.value, CacheOrigin.NETWORK))
            }
            is FetchOutcome.Failure -> if (cached == null) {
                emit(CacheResult.Error(KacheException.NetworkException(outcome.cause)))
            }
            FetchOutcome.Absent -> if (cached == null) {
                emit(CacheResult.Error(KacheException.CacheMissException(NO_FETCHER_MESSAGE)))
            }
        }
    }

    private fun staleWhileRevalidate(key: K, fetcher: (suspend (K) -> V)?): Flow<CacheResult<V>> = flow {
        val cached = readCache(key, allowStale = true)
        if (cached != null) {
            emit(CacheResult.Success(cached.value, cached.origin))
        }

        when (val outcome = fetch(key, fetcher)) {
            is FetchOutcome.Success -> {
                write(key, outcome.value)
                emit(CacheResult.Success(outcome.value, CacheOrigin.NETWORK))
            }
            is FetchOutcome.Failure -> if (cached == null) {
                emit(CacheResult.Error(KacheException.NetworkException(outcome.cause)))
            }
            FetchOutcome.Absent -> if (cached == null) {
                emit(CacheResult.Error(KacheException.CacheMissException(NO_FETCHER_MESSAGE)))
            }
        }
    }

    /**
     * Reads a value from storage.
     *
     * A record that fails to decode is removed, since leaving it in place would make
     * every subsequent read fail the same way.
     */
    private suspend fun readCache(key: K, allowStale: Boolean): Cached<V>? {
        val stringKey = keyToString(key)

        val record = try {
            storageEngine.get(stringKey)
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            throw KacheException.DiskReadException(e)
        } ?: return null

        val stale = record.isExpired(Clock.System.now().toEpochMilliseconds())
        if (stale && !allowStale) return null

        val value = try {
            valueSerializer.deserialize(record.data)
        } catch (e: Exception) {
            storageEngine.remove(stringKey)
            throw if (e is KacheException.SerializationException) e else KacheException.SerializationException(e)
        }

        val origin = if (stale) CacheOrigin.DISK_STALE else CacheOrigin.DISK
        return Cached(value, origin)
    }

    private suspend fun fetch(key: K, fetcher: (suspend (K) -> V)?): FetchOutcome<V> {
        if (fetcher == null) return FetchOutcome.Absent
        return try {
            FetchOutcome.Success(fetcher(key))
        } catch (e: Exception) {
            FetchOutcome.Failure(e)
        }
    }

    private suspend fun write(key: K, value: V) {
        val record = StorageRecord.create(value, valueSerializer, createdAt = now())
        try {
            storageEngine.put(keyToString(key), record)
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(e)
        }
    }

    override suspend fun put(key: K, value: V, ttlMs: Long?) {
        val record = StorageRecord.create(value, valueSerializer, createdAt = now(), ttlMillis = ttlMs)
        try {
            storageEngine.put(keyToString(key), record)
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(e)
        }
    }

    /** Writes a value that never expires. */
    public suspend fun put(key: K, value: V): Unit = put(key, value, ttlMs = null)

    override suspend fun invalidate(key: K) {
        try {
            storageEngine.remove(keyToString(key))
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(e)
        }
    }

    override suspend fun clear() {
        try {
            storageEngine.clear()
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(e)
        }
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private class Cached<T>(val value: T, val origin: CacheOrigin)

    private companion object {
        private const val NO_FETCHER_MESSAGE: String = "No fetcher provided and cache miss"
        private const val NO_DATA_AFTER_FETCH_MESSAGE: String =
            "Network fetch failed and no cached data available"
    }
}

/** Outcome of invoking a fetcher, separating absence of a fetcher from a real failure. */
private sealed interface FetchOutcome<out T> {
    class Success<T>(val value: T) : FetchOutcome<T>
    class Failure(val cause: Exception) : FetchOutcome<Nothing>
    data object Absent : FetchOutcome<Nothing>
}
