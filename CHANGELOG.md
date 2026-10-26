# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). From `1.0.0` on, a minor release
only adds: removals and signature changes wait for a major, and a removal is preceded by at
least one minor release of `@Deprecated`. Pre-`1.0` releases were explicitly allowed to break.

## [1.0.0] — 2026-10-26

### Migration from 0.1.0

Two source-level changes, both small, and one reason to be glad you are upgrading:

- **`CacheResult.Loading` is gone.** Delete the `is Loading ->` branch from any `when` over
  `CacheResult`; it was never emitted, so the branch was dead code. Read progress from
  `CachedState.isLoading` instead (the `KacheStateHolder` view layer exposes it, and keeps the
  last value while a revalidating strategy refreshes in the background).
- **Recompile against 1.0.0.** `KacheLogger` became a constructor parameter on the cache
  factories, which is source-compatible but binary-incompatible: a library built against
  `0.1.0` must be rebuilt, not just re-resolved.
- **The file backend now runs on iOS**, without raising `minSdk` anywhere. `FileStorageEngine`
  is still the same class; `createFromCachesDirectory()` is the iOS counterpart of
  `createFromContext()`. Records written by `0.1.0` remain readable — the `v2` byte layout did
  not change.

### Added

**`:cache-storage` — a file backend on every target**
- `FileStorageEngine` now runs on iOS, so the durable tier no longer has to be Room: the
  engine's algorithm (record layout, key encoding, eviction ranking, temp-then-move writes)
  moved to `commonMain` and only the file operations stayed platform-specific — `java.io` on
  JVM and Android, kotlinx-io on iOS. A straight port to kotlinx-io was not possible: its JVM
  file system is built on `java.nio.file`, which needs Android API 26 while the library
  supports 23. The dependency is `implementation` and no kotlinx-io type appears in the public
  API; no consumer needs to declare it.
- `createFromCachesDirectory(cacheDirName)` for iOS, the counterpart of `createFromContext` on
  Android: it resolves `NSCachesDirectory` so a caller does not have to know which Foundation
  constant is the right one.
- The record byte format is unchanged. `GoldenRecordTest` reads a record written by 0.1.0 on
  every target, so an upgrade does not silently invalidate a consumer's disk cache.
- `FileStorageEngineContractTest` in `commonTest` runs the same assertions on JVM, Android host
  and the iOS simulator, including key-encoding injectivity, path-traversal keys, the size
  limit, reaping and eviction order.

**Hard tests — `:cache-storage`**
- Property tests over the record codec: every prefix of a valid record is refused instead of
  misread, a declared length longer than the payload is refused without being narrowed (the
  `2^32` → `0` and `Long.MAX_VALUE` → negative-offset bugs), a shorter one returns exactly the
  declared bytes, and arbitrary bytes are only ever refused as format errors — never an
  `IndexOutOfBoundsException`, a negative-size allocation or an out-of-memory.
- A nesting sweep for the JSON guard: accepted to 64 levels, refused above it at any depth up
  to 100 000, with brackets inside strings and escapes not counted.
- Chaos tests on real threads: readers during writers, `clear()` during writes, eviction during
  writes. They assert the property a cache needs — a record is whole or absent — rather than an
  internal order, and the failed-write case is driven deterministically through the `FileOps`
  seam instead of depending on how a race happens to schedule.

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

**`:cache-storage` on iOS**
- `:cache-storage` publishes `iosArm64` and `iosSimulatorArm64` klibs carrying
  `KacheSerializer`, `StringSerializer`, `KotlinxJsonSerializer` and, since the file backend
  became cross-platform, `FileStorageEngine`. The serializers alone are what unblocks an iOS
  app that puts `:cache-store-room` behind a cache: without them it had to reimplement
  `KacheSerializer` itself, including the JSON nesting guard.
- The JVM-only `StackOverflowError` guard in `KotlinxJsonSerializer` became `expect`/`actual`:
  the type does not exist on Kotlin/Native, where stack exhaustion aborts the process.
- The serializer tests moved to `commonTest` and now run on the simulator as well.

**`:cache-core` view layer — fixes found by using it**
- `CachedState.isLoading` could never become true: the cache pipeline emits results only, never
  `CacheResult.Loading`, so the flag had no source. `KacheStateHolder` now sets it when a read
  starts and clears it when the read's flow completes, which also covers revalidating
  strategies — with `StaleWhileRevalidate` or `CacheAndNetwork` the cached value arrives while
  the refresh is still in flight, and the flag stays true for that whole window.
- A read for a different key no longer keeps the value of the previous key. `Loading` folded
  into the previous state on purpose, but for a new key that meant rendering the old key's data
  under the new key's title while it loaded — the opposite of what the class documents.
- `isLoading` is now cleared by the current read only: a superseded read must not switch off
  the spinner of the read that replaced it.

**Sample — Compose Multiplatform**
- The sample is now one shared app on Android, desktop and iOS: `:sample` holds all of
  `domain`, `data`, `ui` and `di`, and each platform entry point chooses only a storage engine
  and an HTTP engine. `:sample-android` is a thin application module, because AGP 9 does not
  allow `com.android.application` and the KMP plugin in one module; `iosApp/` is an
  xcodegen-described Xcode project.
- A third screen shows NHTSA Canadian Vehicle Specifications for a chosen model year, cached
  with `StaleWhileRevalidate` — the last strategy the sample had not exercised.
- Every cache result event raises a snackbar (`hit`, `miss`, `retry`, `error`, `evict`);
  `fetch` attempts stay in the panel, where they explain a sequence without interrupting.
- The sample's own dependency catalog lives in `gradle/sample.versions.toml`: Compose
  Multiplatform, Ktor and the lifecycle forks are no longer part of the library's catalog.
- Every list is driven by the library's `KacheStateHolder`, and the repository hands over key,
  strategy and fetcher as one `CacheRequest`; the sample no longer folds `CacheResult` itself.
  `KacheableViewModel` is an `AndroidViewModel` in an Android-only module and therefore cannot
  be used from `commonMain` — the sample uses the portable half it wraps.
- The caches hold domain models, not DTOs, since a holder publishes what the cache stores;
  domain models are `@Serializable` for that reason.
- 32 sample tests: 22 shared by all three targets and 10 ViewModel tests on the JVM.

**CI**
- An `instrumented` job runs the 20-test Room suite on an API 34 emulator, closing the gap
  that `BundledSQLiteDriver` cannot be loaded in a host JVM.
- The iOS job compiles and links `:cache-storage` and the sample framework, and runs the
  `:cache-storage` and `:sample` simulator suites.

### Changed

- `KacheLogger` is a new last parameter on the cache constructors and `l2Cache` factories.
  Source-compatible, binary-incompatible: a library built against 0.1.0 must be recompiled.
- `:cache-storage` gained iOS targets, so its publication set now includes two more klibs.
  Nothing is removed; the JVM and Android artifacts are unchanged.
- The sample's module layout changed (`:sample` + `:sample-android` + `iosApp`). It is not
  published, so no consumer is affected.
- `FileStorageEngine.clear()` documents what happens when a write is in flight: the writer's
  temporary file is deleted underneath it, so that write fails with a `DiskWriteException`
  rather than being silently dropped. A record is never left half-written either way.
- `FileStorageEngine.size()` no longer counts a file it did not write. Temporary files and
  names the key encoding cannot have produced are excluded, the same rule
  `evictionCandidates` already applied. The contract always said "approximate number of
  records held", so nothing a caller could rely on changes; a stray file no longer makes
  capacity management evict a live record early.

### Removed

- `CacheResult.Loading`. It was published in 0.1.0 but no strategy ever emitted it — the
  pipeline publishes outcomes only — so every `is Loading ->` branch a consumer wrote was dead
  code. Progress is not a result: `CachedState.isLoading` carries it, driven by
  `KacheStateHolder`, and it holds across the whole window in which a revalidating strategy has
  already returned the cached value. Emitting a placeholder instead would have changed the
  emission count of `CacheFirst` from one to two, so a caller taking `first()` would have
  received "wait" where it used to receive data.
  **What a consumer has to do:** delete the `Loading` branch from `when` expressions over
  `CacheResult`, and read progress from `CachedState.isLoading` (or from a `KacheStateHolder`).
  Nothing else in the hierarchy changed.

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
