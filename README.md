# Kache

[![Maven Central](https://img.shields.io/badge/maven--central-0.1.0--SNAPSHOT-blue)](https://search.maven.org/artifact/com.github.slavikjunior.kache/cache-core)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.21-blue.svg?logo=kotlin)](http://kotlinlang.org)
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
| `:cache-store-room` (L2 Room/SQLite backend) | ✅ | ❌ | ❌ |

> **Note**: iOS currently only supports L1 in-memory caching. L2 persistent storage for iOS is planned for Phase 4.
> `:cache-store-room` is JVM-only for now; an Android build of the Room backend is planned for `:cache-android`.

## Installation

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // For SNAPSHOT versions:
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
}

// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.github.slavikjunior.kache:cache-core:0.1.0-SNAPSHOT")
            
            // Optional: file-based L2 storage (JVM/Android only)
            implementation("com.github.slavikjunior.kache:cache-storage:0.1.0-SNAPSHOT")
        }
    }
}

// JVM only: Room/SQLite L2 backend
dependencies {
    implementation("com.github.slavikjunior.kache:cache-store-room:0.1.0-SNAPSHOT")
}
```

## Quick Start

### 1. L1 Memory Cache (All Platforms)

```kotlin
import com.github.slavikjunior.kache.core.*
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
import com.github.slavikjunior.kache.core.*
import com.github.slavikjunior.kache.storage.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
data class User(val id: String, val name: String)

// Create L2 cache backed by file storage
val cache = L2KmpCache(
    storageEngine = FileStorageEngine(rootDirectory = "/path/to/cache"),
    serializer = KotlinxJsonSerializer(serializer<User>()),
    keyToString = { it }, // Use key.toString() as storage key
    defaultTtlMs = 24 * 60 * 60 * 1000, // 24 hours
    retryPolicy = RetryPolicy.Exponential(totalAttempts = 3, initialDelayMs = 100),
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
    l1 = L1MemoryCache(maxSize = 50),
    storageEngine = FileStorageEngine("/cache"),
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
import com.github.slavikjunior.kache.core.*
import com.github.slavikjunior.kache.store.room.*
import kotlinx.serialization.serializer

// Bundled native SQLite: no external JDBC driver, no Android framework
val engine: StorageEngine = RoomStorageEngineFactory.createFromFile("/var/data/kache.db")

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
RetryPolicy.Exponential(totalAttempts = 3, initialDelayMs = 100)

// Convenience presets
RetryPolicy.exponential() // 3 attempts, 100ms initial
RetryPolicy.aggressive()  // 5 attempts, 50ms initial
RetryPolicy.fixed(attempts = 2, delayMs = 500)
```

### 7. Testing with MutableTimeSource

```kotlin
import com.github.slavikjunior.kache.core.*

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

## Roadmap

- ✅ **Phase 1-3**: Core abstractions, L1 memory cache, strategy pipeline
- ⏳ **Phase 4**: L2 backends (✅ file, ✅ Room/SQLite on JVM, ⏳ Android Room, ⏳ iOS L2)
- ⏳ **Phase 5**: Android KTX (ViewModel extensions, WorkManager sync)
- ✅ **Phase 6**: Library readiness (API exposure, explicitApi, Maven Publish, Dokka, BCV)
- ⏳ **Phase 7**: AI SDLC metrics, article, conference talk

See [`docs/BACKLOG.md`](docs/BACKLOG.md) for details.

## Requirements

- **Kotlin**: 2.3.21+
- **Coroutines**: 1.11.0+
- **Targets**: JVM 17+, Android API 23+, iOS 13+

## License

[MIT License](LICENSE)

## Contributing

Contributions are welcome! Please read [`AGENTS.md`](AGENTS.md) for development workflow and architecture constraints.

---

**Built with ❤️ and AI assistance** — This project explores the intersection of enterprise KMP development and AI-powered SDLC.
