# Kache Architecture Overview

## Overview
**Kache** is a multi-tiered, reactive Kotlin Multiplatform caching library designed to bridge Network, Memory (L1), and Disk (L2) layers with a single declarative API.

## Modules

| Module | Targets | Contents |
|---|---|---|
| `:cache-core` | JVM, Android, iOS (arm64 / simulatorArm64 / x64) | Domain model, strategy pipeline, `L1MemoryCache`, `ChainKmpCache`, `L2KmpCache`, `StorageEngine` contract |
| `:cache-storage` | JVM, Android | `FileStorageEngine` backend, record file codec, serializers |

Layering rule: `:cache-core` owns *policy* (how a strategy behaves), `:cache-storage` owns *backends* (where bytes are kept). A cache cannot decide on its own where data lives, so a backend is always plugged in through `StorageEngine`.

Known platform gap: `:cache-storage` has no iOS target, so on iOS only the L1 tier is available. Persistent storage on iOS is not implemented yet.

## Core Data Flow

```
[ UI / Presenter / ViewModel ]
        │  get(key, strategy, fetcher)
        ▼
[ Kache Pipeline ]  ← all strategy logic lives here
┌───────┴──────────────────────────┐
▼                               ▼
┌───────────┐                 ┌──────────────┐
│ L1 Cache  │ ── (Miss) ────► │  L2 Cache    │
│ (Memory)  │ ◄─ (Promote) ── │ (StorageEngine) │
└───────────┘                 └──────────────┘
│                               │
(Miss)                          (Miss)
└───────────────┬───────────────┘
                ▼
        [ Fetcher / Network ]
```

## Core Abstractions

### 1. States & Results (`CacheResult<T>`)
Every stream operation returns `Flow<CacheResult<T>>`:
- `Success(data: T, origin: CacheOrigin)` — including the `_STALE` variants, which tell the caller that a refresh follows.
- `Loading(cachedData: T?)` — reserved for asynchronous refresh sources; the current strategies never emit it.
- `Error(error: KacheException, cachedData: T?)` — provides fallback data alongside domain errors.

### 2. Typed Exceptions (`KacheException`)
Extends `Exception`:
- `NetworkException(cause)`
- `DiskReadException(cause)`
- `DiskWriteException(cause)`
- `SerializationException(cause)`
- `CacheMissException(message)` — nothing cached and no fetcher given
- `ExpiredException(key)` — reserved for callers that need to distinguish expiry from a plain miss
- `UnknownKacheException(cause)`

### 3. Fetch Strategies (`CacheStrategy`)
- `CacheFirst`: Check L1 -> L2 -> Fetch Network.
- `NetworkFirst`: Fetch Network -> Fallback to L1/L2 on error.
- `CacheAndNetwork`: Immediately emit L1/L2 cached data, then refresh from Network.
- `StaleWhileRevalidate`: Return even-expired cached value instantly; refresh in background.

An expired record is a miss for every strategy except `StaleWhileRevalidate`. A fetch failure is only surfaced when there is no cached value to fall back on.

### 4. Time (`TimeSource`)
TTL decisions never read the system clock directly. `TimeSource` is the single injection point:
- `SystemTimeSource` — production default.
- `MutableTimeSource` — for tests, so expiry can be reached without waiting.

### 5. Retries (`RetryPolicy`)
`RetryPolicy.None` by default, so a fetcher runs exactly once. `RetryPolicy.Exponential` (or the `exponential()`, `aggressive()`, `fixed()` presets) adds exponential backoff with optional jitter. Coroutine cancellation is never retried and never reported as a network failure.

## Extension Points
- `StorageEngine` — persistent backend contract; translate low-level failures into `DiskReadException` / `DiskWriteException`.
- `KacheSerializer<T>` — value (de)serialization; report failures as `SerializationException`.
- `keyToString: (K) -> String` — required whenever `Any.toString()` is not a stable storage key (data classes with unstable `hashCode`, etc.).