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

## Implementation Status

Superseded in part by the final design; kept for traceability.

- TTL decisions go through the public `TimeSource` abstraction (`SystemTimeSource`, `MutableTimeSource`) instead of reading `Clock.System` directly.
- Promotion from L2 to L1 uses the *remaining* TTL, never the original, so promoting a record cannot extend its lifetime.
- Strategy logic is not implemented inside `ChainKmpCache`: it lives in the internal `CachePipeline`, which `ChainKmpCache` and `L2KmpCache` both delegate to. `CacheTier` (`ChainTier`, `StorageTier`) supplies the tier-specific read/write primitives, so a single-tier cache behaves identically to a two-tier one.
- `defaultTtlMs` is also applied to `KmpCache.put(key, value)` when the caller gives no TTL.
- Tag-based invalidation was descoped: see `docs/BACKLOG.md`, Phase 2.