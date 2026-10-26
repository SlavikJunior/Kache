# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the major version is `0`, a
minor release may contain breaking changes; each one is listed with what a consumer has to do.

## [Unreleased]

### Added

**Observability — `:cache-core`**
- `KacheLogger`: an injectable observer with no-op defaults for `onMiss`, `onHit`, `onFetch`,
  `onRetry`, `onError` and `onEvict`. Pass it to `L2KmpCache`, `ChainKmpCache`, or either
  `l2Cache` factory. Cached values are never passed to it, and keys arrive already converted
  through `keyToString`, so a sensitive key is redacted where the cache is configured rather
  than inside every implementation.
- An `evict` event is emitted only for records dropped to honour `maxSize`; expired records
  removed by a reap are not evictions, because nothing was given up to make room.
- Statistics are not a separate API: an implementation counts the events it receives. The
  sample does exactly that, which keeps the shape of "statistics" an application decision.

**Sample — `:sample-android`**
- Rebuilt as a car catalogue over the public NHTSA vPIC API (Ktor + kotlinx.serialization),
  split into `domain`, `data`, `ui` and `di` packages inside the single module.
- Demonstrates `CacheFirst` for the make list, `NetworkFirst` for models — so an offline
  device still shows what it opened before — and `CacheAndNetwork` for an explicit refresh.
- A live cache panel renders `KacheLogger` events and a hit ratio computed in the app.

**CI**
- An `instrumented` job runs the 20-test Room suite on an API 34 emulator, closing the gap
  that `BundledSQLiteDriver` cannot be loaded in a host JVM.

### Changed

- `KacheLogger` is a new last parameter on the cache constructors and `l2Cache` factories.
  Source-compatible, binary-incompatible: a library built against 0.1.0 must be recompiled.

## [0.1.0] — 2026-10-26

First release. Coordinates are `io.github.slavikjunior.kache:cache-core`, `cache-storage`,
`cache-store-room` and `cache-android`.

### Added

**Core — `:cache-core` (JVM, Android, iOS)**
- `KmpCache<K, V>`: `get` returns a `Flow<CacheResult<V>>`, alongside `put`, `invalidate` and
  `clear`. `CacheResult` is `Loading` / `Success` / `Error`, and `Success` carries a
  `CacheOrigin`, so a caller can tell a memory hit from a disk or network one.
- Four strategies over one pipeline: `CacheFirst`, `NetworkFirst`, `CacheAndNetwork`,
  `StaleWhileRevalidate`.
- `L1MemoryCache`: `Mutex`-guarded, TTL with an injectable `TimeSource`, `getStale` and
  `removeExpired`.
- `ChainKmpCache` (L1 + L2) and `L2KmpCache` (persistent tier only).
- `RetryPolicy` (`None` or backoff) wired into the fetch, cancellation-safe.
- Capacity and eviction: `maxSize` in records, `EvictionStrategy` (`LRU`, `FIFO`, `MRU`, `LIFO`),
  `touchGranularity` to bound the writes LRU would otherwise make on every read, `autoReapEvery`
  (off by default) and `stopAutoReap()`. Eviction prefers an expired record over a live one in
  both tiers, so dead entries never displace useful ones.
- `StorageEngine`: the persistent backend contract. Three of its eight members are defaulted, so
  a backend compiles without implementing an optional feature — which then silently does nothing,
  which is why each default is spelled out in its KDoc.
- `KacheException` hierarchy: `NetworkException`, `DiskReadException`, `DiskWriteException`,
  `SerializationException`, `CacheMissException`, `UnknownKacheException`.
- `CachedState` / `KacheStateHolder`: request state as a `StateFlow`, free of Android types, so it
  is usable from `commonMain`, from a repository and from Swift.

**Storage — `:cache-storage` (JVM, Android)**
- `FileStorageEngine`: atomic writes, a corrupt or foreign record reads as a miss, and a
  key → filename mapping that can neither collide nor escape the cache directory.
- `StringSerializer` and `KotlinxJsonSerializer`.

**Room — `:cache-store-room` (JVM, Android, iOS arm64 and simulator arm64)**
- `RoomStorageEngine` on bundled native SQLite, with `createFromContext(Context)` on Android and
  `createFromCachesDirectory(...)` on iOS.
- Schema 2 with a migration that backfills `last_accessed_at` from `created_at`.
- No `iosX64`: `androidx.sqlite` publishes no such variant.

**Android — `:cache-android`**
- `l2Cache(...)` in two shapes: over a `StorageEngine`, or over a `Context`.
- `clearWhenScopeCancelled(scope)`, and `registerCacheMemoryPressureCallbacks` /
  `unregister…`, which clear on `TRIM_MEMORY_COMPLETE` and `onLowMemory` only.
- `KacheableViewModel`: an `AndroidViewModel` over `KacheStateHolder`.

**Release infrastructure**
- Maven publications with POM metadata, sources JARs and Dokka-rendered `javadoc` JARs.
- Binary compatibility validation for the JVM API and the iOS klib ABI.
- Gradle dependency verification: every jar, klib and module descriptor pinned by SHA-256.
  Documentation artifacts are trusted by name, because they never reach a compile classpath.
- `verifyLocalPublication`, which fails when a publication loses its sources or `javadoc`
  artifact, its descriptor, or a whole target.
- Consumer harnesses (JVM, plain AGP, KMP with an `iosX64` probe) that resolve the published
  coordinates instead of project dependencies.
- 521 tests on JVM, Android host and iOS simulator from shared sources, plus a 20-test
  instrumented suite for the Room backend, and three CI jobs covering all of it.

### Notes for early adopters

- `KacheException.ExpiredException` does not exist: an expired record is a miss, not an error.
- The file cache's key → filename encoding changed. Records written by the previous encoding are
  neither read nor migrated.
- An existing Room database is migrated to schema 2. A database written by a newer schema is
  rejected rather than guessed at.
