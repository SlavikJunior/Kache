# Spec 01: Core Domain API & Error Model

## Target Module
`:cache-core` (`commonMain`)

## Scope & Architectural Boundary
- This is a pure infrastructure SDK. 
- NO Presentation (UI, Composable), Domain UseCase, or Application-specific Repository layers.
- Exposes a minimal, declarative API with `internal` implementations.

## Models Specification

### 1. `CacheOrigin` (Enum)
- `MEMORY`
- `DISK`
- `NETWORK`

### 2. `KacheException` (Sealed Class hierarchy inheriting `Throwable`)
- `NetworkException(override val cause: Throwable)`
- `DiskReadException(override val cause: Throwable)`
- `DiskWriteException(override val cause: Throwable)`
- `SerializationException(override val cause: Throwable)`
- `ExpiredException(val key: Any)`
- `UnknownKacheException(override val cause: Throwable)`

### 3. `CacheResult<out T>` (Sealed Interface)
- `Success<T>(val data: T, val origin: CacheOrigin)`
- `Loading<T>(val cachedData: T? = null)`
- `Error<T>(val error: KacheException, val cachedData: T? = null)`

### 4. `CacheStrategy` (Enum / Sealed Class)
- `CacheFirst`
- `NetworkFirst`
- `CacheAndNetwork`
- `StaleWhileRevalidate`

### 5. `KmpCache<K : Any, V : Any>` (Interface)
```kotlin
interface KmpCache<K : Any Any, V> {
    fun get(
        key: K, 
        strategy: CacheStrategy = CacheStrategy.CacheFirst,
        fetcher: (suspend (K) -> V)? = null
    ): Flow<CacheResult<V>>

    suspend fun put(key: K, value: V, ttlMs: Long? = null)
    suspend fun invalidate(key: K)
    suspend fun clear()
}