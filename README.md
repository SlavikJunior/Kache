# Kache

[![Maven Central](https://img.shields.io/badge/maven--central-0.1.0-blue)](https://central.sonatype.com/artifact/io.github.slavikjunior.kache/cache-core)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.20-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Enterprise-grade Kotlin Multiplatform caching library with L1/L2 tiers and reactive strategy pipeline.

## Features

- **🎯 Multi-Tier Architecture**: L1 (in-memory) + L2 (persistent) caching with automatic promotion
- **⚡ Reactive API**: `Flow`-based results with `CacheFirst`, `NetworkFirst`, `CacheAndNetwork`, `StaleWhileRevalidate` strategies
- **🔄 Retry Policy**: Exponential backoff with jitter, configurable attempts
- **⏰ TTL Management**: Per-entry and default TTL with injectable `TimeSource` for testing
- **🛡️ Type-Safe Exceptions**: Structured `KacheException` hierarchy (`NetworkException`, `DiskReadException`, `SerializationException`, etc.)
- **🌍 Kotlin Multiplatform**: JVM, Android, iOS (arm64, simulatorArm64, x64)
- **📦 Zero Dependencies**: Only coroutines and datetime (transitive from `api`)

## Platform Support

| Module | JVM | Android | iOS |
|---|:---:|:---:|:---:|
| `:cache-core` (L1 memory + abstractions) | ✅ | ✅ | ✅ |
| `:cache-storage` (L2 file backend) | ✅ | ✅ | ❌ |
| `:cache-store-room` (L2 Room/SQLite backend) | ✅ | ✅ | ✅¹ |
| `:cache-android` (Android conveniences) | ❌ | ✅ | ❌ |
| `:sample-android` (Compose demo app) | ❌ | ✅ | ❌ |

> ¹ `:cache-store-room` supports `iosArm64` and `iosSimulatorArm64`. There is no `iosX64`
> variant because `androidx.sqlite` 2.7.1 does not publish one, so the dependency cannot
> resolve for that target. `:cache-core` does support `iosX64`.

## Android Conveniences

`:cache-android` is a thin layer over `:cache-core` and `:cache-storage`; it adds no
storage backend and pulls in neither Room nor WorkManager.

```kotlin
// L2 cache in the app's private cache directory
val cache: L2KmpCache<String, User> = l2Cache(
    context = applicationContext,
    serializer = KotlinxJsonSerializer(serializer<User>()),
)

// Clear it when the screen goes away (pass viewModelScope)
cache.clearWhenScopeCancelled(viewModelScope)

// React to Android killing the process under memory pressure
val callbacks = application.registerCacheMemoryPressureCallbacks(appScope, cache)
```

Intermediate trim levels such as `UI_HIDDEN` deliberately keep the data: the process is
still alive, and clearing there would turn a survivable trim into a visible reload. Only
`TRIM_MEMORY_COMPLETE` and `onLowMemory` evict.

### Caching ViewModel

`KacheableViewModel` removes the plumbing every caching screen repeats: a `StateFlow`, a
job per read, and the mapping from cache events onto view state.

```kotlin
class ProfileViewModel(app: Application) :
    KacheableViewModel<String, Profile>(app, cache = l2Cache(app, serializer)) {

    init {
        load(key = "profile-42", fetcher = { repository.load("profile-42") })
    }
}
```

```kotlin
// The screen holds no state of its own.
val state by viewModel.kacheState.collectAsStateWithLifecycle()

Text(state.data?.name ?: "—")
Text("origin=${state.origin}")
if (state.isLoading) CircularProgressIndicator()
state.error?.let { Button(onClick = viewModel::retry) { Text("Retry") } }
```

A refresh leaves `data` populated and flips `isLoading`, so the screen dims instead of
blanking. The state type is deliberately framework-free and lives in `cache-core`, so the
same holder works from `commonMain`, from a repository, and from Swift:

```kotlin
// Cross-platform: no Android types involved
val holder = KacheStateHolder(scope = myScope, cache = myCache)
holder.load(key = "profile-42", fetcher = { repository.load("profile-42") })
```

`load` is `open`, so a subclass can override it to add behaviour such as logging, without
being forced to forward a call it does not need to change.

### Tuning retries

Retries default to `RetryPolicy.None`: a cache should not amplify traffic during an
outage. Opt in when a second attempt is genuinely worth it.

```kotlin
l2Cache(
    context = applicationContext,
    serializer = KotlinxJsonSerializer(serializer<User>()),
    // 3 attempts, exponential backoff, 20% jitter so clients do not retry in lockstep
    retryPolicy = RetryPolicy.Exponential(maxAttempts = 3, initialDelayMs = 200L),
)
```

The companion offers untuned `exponential()`, `aggressive()` (5 retries from 250 ms) and
`fixed(attempts, delayMs)`; anything else goes through the `Exponential` constructor.

> Note: the `-ktx` Lifecycle artifacts became empty aliases in 2.9. `viewModelScope` and
> `AndroidViewModel` now live in `androidx.lifecycle:lifecycle-viewmodel`, which is what
> this module depends on.

## Sample App

`:sample-android` is a single-Activity Jetpack Compose app (Material 3) that exercises
`L2KmpCache` against `FileStorageEngine.createFromContext()` on a real device. Its
`SampleViewModel` runs a scenario and renders one card per step:

| Step | Expectation |
|---|---|
| Cold read with fetcher | `CacheOrigin.NETWORK` on a clean install, `CacheOrigin.DISK` if a previous run left an entry |
| Warm read, no fetcher | `CacheOrigin.DISK` — a hit without any network |
| Read after `invalidate` | `CacheMissException`, which also proves the file was deleted |
| Entry left for the next launch | `StorageEngine.size() > 0`, so a relaunch reads from disk |

That last step is the point of the sample: after `force-stop` + relaunch the first read
reports `CacheOrigin.DISK`, which shows the record survived process death rather than
living only in memory.

```bash
./gradlew :sample-android:assembleDebug
adb install -r sample-android/build/outputs/apk/debug/sample-android-debug.apk
```

The sample deliberately does **not** depend on `:cache-store-room`: that module ships
bundled SQLite for the JVM and cannot run on Android.

## Installation

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.slavikjunior.kache:cache-core:0.1.0")

            // Optional: file-based L2 storage (JVM/Android only)
            implementation("io.github.slavikjunior.kache:cache-storage:0.1.0")
        }
    }
}

// JVM only: Room/SQLite L2 backend
dependencies {
    implementation("io.github.slavikjunior.kache:cache-store-room:0.1.0")
}
```

> **Note**: `0.1.0` is verified against a local Maven repo (`build/repo`) only. No
> Central Portal repository is configured yet, so the coordinates are not resolvable
> from `mavenCentral()` until the release is actually published.

## Quick Start

### 1. L1 Memory Cache (All Platforms)

```kotlin
import io.github.slavikjunior.kache.core.*
import kotlinx.coroutines.flow.collect

// Create L1 cache with LRU eviction (maxSize=100) and 5-minute default TTL
val cache = L1MemoryCache<String, User>(
    maxSize = 100,
    defaultTtlMs = 5 * 60 * 1000,
    timeSource = SystemTimeSource
)

// Fetch with CacheFirst strategy (check cache -> network on miss)
cache.get(
    key = "user:123",
    strategy = CacheStrategy.CacheFirst,
    fetcher = { key -> api.fetchUser(key) }
).collect { result ->
    when (result) {
        is CacheResult.Success -> {
            println("User: ${result.data}, from: ${result.origin}")
        }
        is CacheResult.Error -> {
            println("Failed: ${result.error}")
            result.cachedData?.let { println("Fallback: $it") }
        }
        is CacheResult.Loading -> {
            println("Loading... (cached: ${result.cachedData})")
        }
    }
}

// Write to cache with custom TTL
cache.put("user:123", user, ttlMs = 10 * 60 * 1000)

// Invalidate entry
cache.invalidate("user:123")

// Clear all
cache.clear()
```

### 2. L2 Persistent Cache (JVM/Android)

```kotlin
import io.github.slavikjunior.kache.core.*
import io.github.slavikjunior.kache.storage.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
data class User(val id: String, val name: String)

// Create L2 cache backed by file storage.
// On Android use FileStorageEngine.createFromContext(context) instead.
val cache = L2KmpCache(
    storageEngine = FileStorageEngine(rootDirectory = "/path/to/cache"),
    valueSerializer = KotlinxJsonSerializer(serializer<User>()),
    keyToString = { it }, // Use key.toString() as storage key
    defaultTtlMs = 24 * 60 * 60 * 1000, // 24 hours
    retryPolicy = RetryPolicy.Exponential(maxAttempts = 3, initialDelayMs = 100),
    timeSource = SystemTimeSource
)

cache.get(
    key = "user:123",
    strategy = CacheStrategy.NetworkFirst, // Always try network first
    fetcher = { api.fetchUser(it) }
).collect { result ->
    // Handle result
}
```

### 3. L1+L2 Two-Tier Cache (JVM/Android)

```kotlin
// Combine memory (L1) + disk (L2) with automatic promotion
val cache = ChainKmpCache(
    l1Cache = L1MemoryCache(maxSize = 50),
    l2Storage = FileStorageEngine("/cache"),
    serializer = KotlinxJsonSerializer(serializer<User>()),
    keyToString = { it },
    defaultTtlMs = 60 * 60 * 1000,
    retryPolicy = RetryPolicy.fixed(attempts = 2, delayMs = 200),
    timeSource = SystemTimeSource
)

// CacheFirst: Check L1 -> L2 -> Network
// On L2 hit, value is promoted to L1 automatically
cache.get("user:123", CacheStrategy.CacheFirst) {
    api.fetchUser(it)
}.collect { /* ... */ }
```

### 4. L2 Room/SQLite Cache (JVM)

```kotlin
import io.github.slavikjunior.kache.core.*
import io.github.slavikjunior.kache.store.room.*
import kotlinx.serialization.serializer

// Bundled native SQLite: no external JDBC driver and no Android framework required.
// Pick the entry point for the platform:
//   JVM      — createFromFile("/var/data/kache.db")
//   Android  — createFromContext(context)
//   iOS      — createFromCachesDirectory()
val engine: StorageEngine = createFromFile("/var/data/kache.db")

val cache = L2KmpCache(
    storageEngine = engine,
    valueSerializer = KotlinxJsonSerializer(serializer<User>()),
    keyToString = { it },
    defaultTtlMs = 24 * 60 * 60 * 1000,
    timeSource = SystemTimeSource
)

// Expired records can be reaped in bulk
val removed = engine.removeExpired(now = System.currentTimeMillis())
```

For full control over the Room configuration (migrations, callbacks, journal mode),
build the database yourself and pass it in:

```kotlin
val db = Room.databaseBuilder<KacheDatabase>(name = "/var/data/kache.db")
    .setDriver(BundledSQLiteDriver())
    .fallbackToDestructiveMigration(dropAllTables = true)
    .build()

val engine = RoomStorageEngineFactory.create(db)
```

### 5. Strategies

```kotlin
// CacheFirst: Use cached value if available, fetch on miss
cache.get(key, CacheStrategy.CacheFirst, fetcher)

// NetworkFirst: Always try network, fallback to cache on error
cache.get(key, CacheStrategy.NetworkFirst, fetcher)

// CacheAndNetwork: Return cache immediately, then refresh from network
cache.get(key, CacheStrategy.CacheAndNetwork, fetcher)
// Emits: Success(data, MEMORY_STALE) -> Success(freshData, NETWORK)

// StaleWhileRevalidate: Return even-expired cache, refresh in background
cache.get(key, CacheStrategy.StaleWhileRevalidate, fetcher)
// Expired record still returned as Success(data, DISK_STALE)
```

### 6. Retry Policies

```kotlin
// No retries (default)
RetryPolicy.None

// Exponential backoff: 100ms, 200ms, 400ms
RetryPolicy.Exponential(maxAttempts = 3, initialDelayMs = 100)

// Convenience presets
RetryPolicy.exponential() // 3 attempts, 100ms initial
RetryPolicy.aggressive()  // 5 retries, 250ms initial
RetryPolicy.fixed(attempts = 2, delayMs = 500)
```

### 7. Testing with MutableTimeSource

```kotlin
import io.github.slavikjunior.kache.core.*

@Test
fun `expired entries are not returned`() = runTest {
    val time = MutableTimeSource(initialTimeMillis = 1000)
    val cache = L1MemoryCache<String, Int>(
        maxSize = 10,
        defaultTtlMs = 500,
        timeSource = time
    )
    
    cache.put("key", 42)
    
    // Advance clock past expiry
    time.advance(600)
    
    val result = cache.get("key").first()
    assertTrue(result is CacheResult.Error)
    assertTrue((result as CacheResult.Error).error is KacheException.CacheMissException)
}
```

## Architecture

```
[ UI / ViewModel ]
    │
    ▼
[ Kache Pipeline ] ← strategy logic (CacheFirst, NetworkFirst, etc.)
    ├─────────────┬───────────────┐
    ▼             ▼               ▼
┌─────────┐  ┌─────────┐   ┌──────────┐
│ L1 (LRU)│  │ L2 (FS) │   │  Fetcher │
│ Memory  │  │  Disk   │   │ (Network)│
└─────────┘  └─────────┘   └──────────┘
```

`L2` is pluggable: `:cache-storage` ships a file backend, `:cache-store-room` a Room/SQLite backend.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for detailed design.

## Documentation

- [Architecture Overview](docs/ARCHITECTURE.md)
- [Project Backlog](docs/BACKLOG.md)
- [Core Domain Spec](docs/specs/01_core_domain_spec.md)
- [L1 Memory Cache Spec](docs/specs/02_l1_memory_cache.md)
- [Platform Coverage & Release Spec](docs/specs/03_platform_coverage_and_release_spec.md)
- [Engineering Metrics](docs/METRICS.md)
- [Article & Talk Outline](docs/ARTICLE_OUTLINE.md)
- [KDoc API Reference](cache-core/build/dokka/html/index.html) (generate with `./gradlew dokkaGeneratePublicationHtml`; output is per module under `<module>/build/dokka/html/`)

## Building

```bash
# Compile all targets
./gradlew assemble

# Run tests (JVM, Android host, iOS simulator)
./gradlew check

# Publish to local Maven repo (build/repo)
./gradlew publishAllPublicationsToLocalRepository

# Generate KDoc HTML (per module: <module>/build/dokka/html/)
./gradlew dokkaGeneratePublicationHtml
```

### Verified release state

`0.1.0` publishes to the local repo at `build/repo` with a `javadoc`-classified JAR, a
`sources` JAR and full POM metadata on every publication. Two things are still missing
before the artifacts can go to Maven Central:

- **No GPG signature.** Export `SIGNING_KEY` and `SIGNING_PASSWORD` as env vars or Gradle
  properties; signing is skipped while they are absent, which is what keeps local
  publication working.
- **No Central Portal repository.** Only the `local` repository is configured, in
  `KachePublishConventionPlugin`.

Binary-compatibility dumps cover the JVM artifacts (`api/jvm/`) and the iOS KLib ABI
(`api/*.klib.api`). The Android target is the one gap: BCV hooks a KMP Android target only
when its compilation is named `release`, while the AGP Kotlin Multiplatform plugin exposes
`main`, so no Android dump is produced. The Android-only public surface is kept to a single
`createFromContext` function, and upgrading BCV did not change this behaviour.

CI lives in `.github/workflows/ci.yml`: JVM + Android tests and the sample on Linux, iOS
compile/link and simulator tests on macOS, and a local-publication dry run.

## Roadmap

- ✅ **Phase 1-3**: Core abstractions, L1 memory cache, strategy pipeline
- ✅ **Phase 4**: L2 backends (✅ file, ✅ Room/SQLite on JVM + Android + iOS)
- ✅ **Phase 5**: Android KTX (`:cache-android`, ViewModel scope, memory pressure; WorkManager deliberately descoped)
- ✅ **Phase 6**: Library readiness (API exposure, explicitApi, Maven Publish, Dokka, BCV)
- ✅ **Phase 7**: AI SDLC metrics ([docs/METRICS.md](docs/METRICS.md)), article outline ([docs/ARTICLE_OUTLINE.md](docs/ARTICLE_OUTLINE.md))

See [`docs/BACKLOG.md`](docs/BACKLOG.md) for details.

## Requirements

- **Kotlin**: 2.4.20+
- **Coroutines**: 1.11.0+
- **Targets**: JVM 17+, Android API 23+, iOS 13+

## License

[MIT License](LICENSE)

## Contributing

Contributions are welcome! Please read [`AGENTS.md`](AGENTS.md) for development workflow and architecture constraints.

---

**Built with ❤️ and AI assistance** — This project explores the intersection of enterprise KMP development and AI-powered SDLC.
