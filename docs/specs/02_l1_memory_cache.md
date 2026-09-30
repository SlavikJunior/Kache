# Spec 02: L1 In-Memory Cache & Chain Pipeline

## Target Module
`:cache-core` (`commonMain`)

## Requirements

### 1. `L1MemoryCache<K : Any, V : Any>`
- Thread-safe in-memory cache using LRU (Least Recently Used) eviction policy.
- Fixed capacity constraint (`maxSize: Int`).
- Support optional Time-To-Live (`defaultTtlMs: Long?`).
- Key-Value operations:
    - `get(key: K): StorageRecord<V>?` (returns record if present and NOT expired; automatically removes if expired).
    - `put(key: K, value: V, ttlMs: Long?)`
    - `remove(key: K)`
    - `clear()`

### 2. Thread-Safety in KMP (`commonMain`)
- Must use Kotlin Coroutines `Mutex` or atomic references (`kotlinx.atomicfu` / thread-safe state) to ensure thread-safety across JVM, Android, and iOS/Native targets.

### 3. Combined Two-Tier Cache Pipeline (`ChainKmpCache<K, V>`)
Integrate L1 and L2 seamlessly:
- **Read Path (`get`):**
    1. Check L1 Memory -> If Hit, emit `Success(data, MEMORY)`.
    2. If Miss in L1, check L2 Storage -> If Hit, promote value to L1 and emit `Success(data, DISK)`.
    3. If Miss in L2, execute `fetcher` (Network) -> On Success, write to L2 + L1 and emit `Success(data, NETWORK)`.
- Respect `CacheStrategy` (`CacheFirst`, `NetworkFirst`, `CacheAndNetwork`, `StaleWhileRevalidate`).