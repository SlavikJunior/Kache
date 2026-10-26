# Kache

<div align="center">

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF.svg?logo=kotlin)](http://kotlinlang.org)
[![AGP](https://img.shields.io/badge/AGP-9.4.1-3DDC84.svg?logo=androidstudio)](https://developer.android.com/studio)
[![Coroutines](https://img.shields.io/badge/coroutines-1.11.0-795548.svg)](https://github.com/Kotlin/kotlinx.coroutines)
[![Room](https://img.shields.io/badge/Room-2.8.5-E8F0FE.svg?logo=android)](https://developer.android.com/kotlin/room)
[![SQLite](https://img.shields.io/badge/androidx.sqlite-2.7.1-E8F0FE.svg)](https://developer.android.com/kotlin/room)
[![KSP](https://img.shields.io/badge/KSP-2.3.12-2E6D82.svg)](https://kotlinlang.org/docs/ksp-overview.html)
[![Lifecycle](https://img.shields.io/badge/Lifecycle-2.11.0-3DDC84.svg?logo=android)](https://developer.android.com/jetpack/androidx/releases/lifecycle)
[![minSdk](https://img.shields.io/badge/minSdk-23-8A8A8A.svg)](https://developer.android.com)
[![Tests](https://img.shields.io/badge/tests-743%20green-1D6B4F.svg)](#project-status)

**English** · [Русский](README.ru.md)

</div>
[![Maven Central](https://img.shields.io/badge/maven--central-0.1.0-blue)](https://central.sonatype.com/artifact/io.github.slavikjunior.kache/cache-core)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A Kotlin Multiplatform cache with two tiers, a reactive strategy pipeline, and one API that
behaves the same on Android and iOS.

```kotlin
cache.get(
    key = "user-42",
    strategy = CacheStrategy.CacheFirst,
    fetcher = { api.loadUser("42") },
).collect { result ->
    when (result) {
        is CacheResult.Success -> render(result.data, origin = result.origin)
        is CacheResult.Error   -> showError(result.error)
    }
}
```

That snippet is the whole idea: you describe *what* you want, the cache decides whether it
comes from memory, from disk or from the network, and it tells you which one it was.
`CacheResult` is a *result*, so it has no "still working" case — a screen that needs progress
uses `CachedState.isLoading`, which the state holder keeps true for as long as the read runs,
including the window in which a refresh already shows the cached value.

---

## Contents

- [Why another cache library](#why-another-cache-library)
- [Platform support](#platform-support)
- [Installation](#installation)
  - [Consumer requirements](#consumer-requirements)
- [Core concepts](#core-concepts)
  - [Tiers and how a read flows through them](#tiers-and-how-a-read-flows-through-them)
  - [Strategies](#strategies)
  - [CacheOrigin: where the value came from](#cacheorigin-where-the-value-came-from)
  - [Exceptions](#exceptions)
- [Recipes](#recipes)
  - [1. In-memory cache (all platforms)](#1-in-memory-cache-all-platforms)
  - [2. Persistent file cache (JVM / Android)](#2-persistent-file-cache-jvm--android)
  - [3. Persistent Room cache (JVM / Android / iOS)](#3-persistent-room-cache-jvm--android--ios)
  - [4. Two-tier cache: memory in front of disk](#4-two-tier-cache-memory-in-front-of-disk)
  - [5. Typed keys instead of strings](#5-typed-keys-instead-of-strings)
  - [6. Caching ViewModel](#6-caching-viewmodel)
  - [7. Clearing a cache with a screen](#7-clearing-a-cache-with-a-screen)
  - [8. Reacting to memory pressure](#8-reacting-to-memory-pressure)
  - [9. Retry policy](#9-retry-policy)
  - [10. Bounding how much L2 keeps](#10-bounding-how-much-l2-keeps)
  - [11. Testing TTL without waiting](#11-testing-ttl-without-waiting)
  - [12. Observing the cache](#12-observing-the-cache)
- [Which backend should I use?](#which-backend-should-i-use)
- [Security considerations](#security-considerations)
- [Dependencies this library brings in](#dependencies-this-library-brings-in)
- [Architecture](#architecture)
- [Building from source](#building-from-source)
- [Project status](#project-status)
- [License](#license)

---

## Why another cache library

There are good general-purpose caches. The gap this fills is narrower:

- **One API for both tiers.** Most libraries make you choose between an in-memory cache and
  a disk cache at the type level. Here `L1MemoryCache` and a `StorageEngine` plug into the
  same `KmpCache` contract, so promoting a value from memory to disk is not your problem.
- **You are told where the data came from.** `CacheOrigin` distinguishes `MEMORY`,
  `DISK`, `NETWORK`, `MEMORY_STALE` and `DISK_STALE`. That is the difference between
  "the screen shows something" and "the screen shows something and I know it is a stale
  revalidation that is still in flight".
- **A refresh does not blank the screen.** `CachedState` keeps the last value while
  `isLoading` is true. Mapping raw cache results onto a `StateFlow` would drop it.
- **Platforms are first-class.** The same cache code runs on Android and iOS, including
  persistent storage, through a pluggable `StorageEngine`.

## Platform support

| Module | JVM | Android | iOS | What it is |
|---|:---:|:---:|:---:|---|
| `:cache-core` | ✅ | ✅ | ✅ | L1 memory cache, strategies, `StorageEngine` SPI |
| `:cache-storage` | ✅ | ✅ | ✅² | File-based L2 and serializers on every target |
| `:cache-store-room` | ✅ | ✅ | ✅¹ | Room/SQLite L2 backend |
| `:cache-android` | ❌ | ✅ | ❌ | Android conveniences (`l2Cache`, caching ViewModel) |
| `:sample` | ✅ | ✅ | ✅ | Compose Multiplatform car catalogue, shared by all three |
| `:sample-android` | ❌ | ✅ | ❌ | Android entry point of the sample |

> ¹ `:cache-store-room` supports `iosArm64` and `iosSimulatorArm64`. There is no `iosX64`
> variant because `androidx.sqlite` 2.7.1 does not publish one, so the dependency cannot
> resolve for that target. `iosX64` is deprecated by Apple and is not offered by the sample.
>
> ² `:cache-storage` publishes one file backend for every target. The engine itself is common
> code; only the file-system calls differ — `java.io` on JVM and Android, kotlinx-io on iOS —
> because kotlinx-io's JVM file system needs Android API 26 while the library supports 23. On
> iOS this means a durable L2 without Room; `:cache-store-room` remains the choice when you
> want queries and migrations.

`0.1.0` is published to Maven Central. The artifacts are signed and resolve from
`mavenCentral()`; see [Project status](#project-status).

## Installation

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

Then pick the modules you need. `cache-core` is the only mandatory one.

```kotlin
// build.gradle.kts of a Kotlin Multiplatform module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.slavikjunior.kache:cache-core:0.1.0")

            // Optional L2 backends. Both expose a StorageEngine; take the one that fits.
            implementation("io.github.slavikjunior.kache:cache-storage:0.1.0")   // JVM/Android
            implementation("io.github.slavikjunior.kache:cache-store-room:0.1.0") // JVM/Android/iOS
        }
    }
}
```

From a **plain Android** module (no `kotlin { }` block at all — the KMP plugin is not
required on your side):

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.slavikjunior.kache:cache-core:0.1.0")
    implementation("io.github.slavikjunior.kache:cache-android:0.1.0")
}
```

Gradle Module Metadata carries a per-platform variant, so a plain Android project resolves
the AAR and never sees the Kotlin/Native artifacts. For `:cache-core` the only things that
reach your classpath are `kotlin-stdlib` and `kotlinx-coroutines`; an L2 backend adds
`kotlinx-serialization` (through `:cache-storage`) or Room/SQLite (through `:cache-store-room`).

### Consumer requirements

| Requirement | Value | Why |
|---|---|---|
| Kotlin | **2.4.20 or newer** | The published metadata is compiled by 2.4.20 and the API uses `kotlin.time.Duration` / `kotlin.time.Clock`. An older compiler cannot read the metadata. |
| Android Gradle Plugin | **9.x** | The Android artifacts are produced by `com.android.kotlin.multiplatform.library`; AGP 9 also provides built-in Kotlin support. |
| `compileSdk` | **36 or newer** | Every library module records `compileSdk 36` in its AAR metadata, and AGP refuses to consume an AAR built against a newer API than the consumer compiles against. |
| `minSdk` | **23** | The floor declared by all four artifacts. |
| Gradle | **9.x** | The build runs on 9.6.1 and publishes standard Gradle Module Metadata. |
| iOS targets | `:cache-core` — `iosArm64`, `iosSimulatorArm64`, `iosX64`; `:cache-store-room` — `iosArm64`, `iosSimulatorArm64` only | There is no `iosX64` variant of `androidx.sqlite` 2.7.1, so a project with an `iosX64` target must keep `:cache-store-room` out of `commonMain`. |

`:cache-storage` is jvm/android-only, so it belongs in `jvmMain`/`androidMain`, never in the
`commonMain` of a project that also targets iOS.

Requires `minSdk 23`.

## Core concepts

### Tiers and how a read flows through them

```
                 ┌──────────────────────────────┐
   get(key) ───▶ │         CachePipeline        │
                 └──────────────┬───────────────┘
                                │
                    ┌───────────▼───────────┐
                    │   CacheStrategy       │
                    │  decides the order    │
                    └───────────┬───────────┘
                                │
              ┌─────────────────┼─────────────────┐
              ▼                 ▼                 ▼
        ┌──────────┐     ┌──────────┐      ┌────────────┐
        │   L1     │     │ fetcher  │      │    L2      │
        │  memory  │     │ network  │      │  Storage   │
        │  (LRU)   │     │          │      │  Engine    │
        └──────────┘     └──────────┘      └────────────┘
```

- **L1** is `L1MemoryCache`: an LRU map with optional per-entry TTL, guarded by a
  `Mutex` so it is safe under concurrency.
- **L2** is anything implementing `StorageEngine`. It is reached only when L1 misses, and
  a value read from L2 is promoted into L1 automatically.
- **The fetcher** is your lambda. The cache calls it only when the chosen strategy needs
  it, so a satisfied cache never touches the network.

A hit on L2 returns `CacheOrigin.DISK`; the same value on the next call returns
`CacheOrigin.MEMORY`, because it was promoted.

### Strategies

| Strategy | Behaviour | Emits |
|---|---|---|
| `CacheFirst` | Serve fresh cache; fetch only on a miss. Expired counts as a miss. | one state |
| `NetworkFirst` | Fetch first; fall back to cache when the fetch fails. | one state |
| `CacheAndNetwork` | Show the cached value immediately, then refresh. | up to two states |
| `StaleWhileRevalidate` | Show the cached value *even if expired*, then refresh. | up to two states |

Two details worth knowing:

- With `CacheFirst`, an expired record is treated as a miss, so a client never receives
  data it would immediately discard.
- With `NetworkFirst`, a failed fetch is **not** reported when cache can take its place —
  the cached value is emitted with its real origin instead.

### CacheOrigin: where the value came from

```kotlin
enum class CacheOrigin {
    MEMORY, MEMORY_STALE,   // read from the in-memory tier
    DISK, DISK_STALE,       // read from persistent storage
    NETWORK,                // produced by the fetcher
}
```

The `_STALE` values only appear with `StaleWhileRevalidate`: they tell you that what is on
screen has already outlived its TTL and a refresh is running behind it.

### Exceptions

Everything the library throws is a `KacheException`, so one `catch` covers it:

```kotlin
sealed class KacheException : Exception {
    class NetworkException(cause)          // the fetcher failed
    class CacheMissException(message)      // nothing cached and no fetcher
    class DiskReadException(cause)
    class DiskWriteException(cause)
    class SerializationException(cause)
    class UnknownKacheException(cause)
}
```

Cancellation is never wrapped: a cancelled coroutine is rethrown as `CancellationException`,
so cancelling a collection cannot be mistaken for a failed request.

---

## Recipes

### 1. In-memory cache (all platforms)

```kotlin
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.L1MemoryCache
import kotlin.time.Duration.Companion.seconds

val cache = L1MemoryCache<String, String>(
    maxSize = 100,          // entries before eviction starts
    defaultTtl = 60.seconds, // null means entries never expire
)

cache.put("greeting", "hello")
cache.get("greeting")          // StorageRecord<String>?
cache.size()                   // entries held
cache.removeExpired()          // reaps what TTL has passed
```

`get` returns `null` for a missing *or* expired key and drops it as a side effect. Use
`getStale` when you deliberately want an expired value.

### 2. Persistent file cache (JVM / Android)

One file per key, written atomically through a temporary file, so a crash mid-write cannot
leave a half-written record behind.

```kotlin
import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.storage.FileStorageEngine
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer
import kotlin.time.Duration.Companion.hours

// The engine overload keeps ownership with you, which you want when you also need
// size(), seeding or closing.
val engine = FileStorageEngine("/data/local/tmp/my-cache")

val cache = L2KmpCache<String, UserProfile>(
    storageEngine = engine,
    valueSerializer = KotlinxJsonSerializer(UserProfile.serializer()),
    defaultTtl = 1.hours,
)
```

On Android there is a `Context`-based shortcut — see [recipe 6](#6-caching-viewmodel) and
the `l2Cache` factory below.

### 3. Persistent Room cache (JVM / Android / iOS)

Useful when you want an indexable store, or when the cache has to live on a platform where
a plain file API is awkward. On iOS this is the **only** L2 backend.

```kotlin
// JVM: a file path whose parent directory already exists
val cache = createFromFile("/tmp/kache.db")

// Android: the app's private files directory
val cache = createFromContext(applicationContext, "kache.db")

// iOS: the app's caches directory
val cache = createFromCachesDirectory("kache.db")
```

Every factory returns a `RoomStorageEngine`, which is a `StorageEngine` — feed it to any of
the cache classes exactly like the file engine.

### 4. Two-tier cache: memory in front of disk

`ChainKmpCache` puts `L1MemoryCache` in front of any `StorageEngine`. This is the
configuration most apps want.

```kotlin
import io.github.slavikjunior.kache.core.ChainKmpCache
import io.github.slavikjunior.kache.core.L1MemoryCache
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

val cache = ChainKmpCache<String, UserProfile>(
    l1Cache = L1MemoryCache(maxSize = 200, defaultTtl = 5.minutes),
    l2Storage = FileStorageEngine("/tmp/my-cache"),
    serializer = KotlinxJsonSerializer(UserProfile.serializer()),
    defaultTtl = 1.days,
)
```

Both tiers are given the same clock, so they cannot disagree about expiry — a value L1
considers fresh will not be reported as fresh while L2 disagrees.

### 5. Typed keys instead of strings

Keys are typed by default and converted with `toString()`, which is fine for IDs and unsafe
for data classes. Supply your own mapping:

```kotlin
val cache = L2KmpCache<UserId, Profile>(
    storageEngine = engine,
    valueSerializer = KotlinxJsonSerializer(Profile.serializer()),
    keyToString = { it.raw },          // instead of UserId(dataClassToString=…)
    defaultTtl = 1.hours,
)
```

The mapping must produce a distinct string per key: distinct keys can collide onto the
same file, which is a silent data-loss bug rather than an error.

### 6. Caching ViewModel

`KacheableViewModel` removes the plumbing every caching screen repeats: a `StateFlow`, a
job per read, and the mapping from cache events onto view state.

```kotlin
import io.github.slavikjunior.kache.android.KacheableViewModel
import io.github.slavikjunior.kache.android.l2Cache
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer

class ProfileViewModel(application: Application) :
    KacheableViewModel<String, Profile>(
        application = application,
        cache = l2Cache(application, KotlinxJsonSerializer(Profile.serializer())),
    ) {

    init {
        load(key = "profile-42", fetcher = { repository.load("profile-42") })
    }
}
```

The screen then holds no state of its own:

```kotlin
val state by viewModel.kacheState.collectAsStateWithLifecycle()

Text(state.data?.name ?: "—")
Text("origin=${state.origin}")
if (state.isLoading) CircularProgressIndicator()
state.error?.let { Button(onClick = viewModel::retry) { Text("Retry") } }
```

`CachedState` keeps `data` populated while `isLoading` is true, so a refresh dims the
screen instead of blanking it. An error may still carry a usable fallback, which is why an
error does not imply empty state.

The state type is deliberately framework-free and lives in `:cache-core`, so the same
holder works outside a ViewModel:

```kotlin
// Cross-platform: no Android types involved
val holder = KacheStateHolder(scope = myScope, cache = myCache)
holder.load(key = "profile-42", fetcher = { repository.load("profile-42") })
```

`load` is `open`, so a subclass can override it to add logging or analytics without being
forced to forward a call it does not need to change.

**Do not put the cache itself in the ViewModel.** A ViewModel is destroyed on every
configuration change, so a cache held in one loses its in-memory tier on each rotation. The
sample application keeps its cache in a process-scoped holder for exactly this reason.

### 7. Clearing a cache with a screen

```kotlin
import io.github.slavikjunior.kache.android.clearWhenScopeCancelled

// Clears when the ViewModel goes away. The returned Job completes once the eviction has
// actually happened, so it is awaitable in tests.
cache.clearWhenScopeCancelled(viewModelScope)
```

The extension fires when the scope finishes, whether it was cancelled or completed. The
clearing deliberately does **not** run inside that scope: it is already finished by then,
so work launched there would either be cancelled immediately or deadlock a caller awaiting
the returned job.

Use an application-scoped scope instead when the cache must outlive every screen.

### 8. Reacting to memory pressure

```kotlin
import io.github.slavikjunior.kache.android.registerCacheMemoryPressureCallbacks
import io.github.slavikjunior.kache.android.unregisterCacheMemoryPressureCallbacks

val callbacks = application.registerCacheMemoryPressureCallbacks(appScope, cache)
// ...
application.unregisterCacheMemoryPressureCallbacks(callbacks)
```

Only `TRIM_MEMORY_COMPLETE` and `onLowMemory` evict. Intermediate levels such as
`TRIM_MEMORY_UI_HIDDEN` deliberately keep the data: the process is still alive and fully
expected back, and clearing there would turn a trim the system considers survivable into a
visible reload.

Unregister when the caches go away, otherwise the application retains them for the rest of
the process lifetime.

### 9. Retry policy

Retries default to `RetryPolicy.None`: a cache should not amplify traffic during an outage.

```kotlin
import io.github.slavikjunior.kache.core.RetryPolicy
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

l2Cache(
    context = applicationContext,
    serializer = KotlinxJsonSerializer(Profile.serializer()),
    // 3 attempts, exponential backoff, 20% jitter so clients do not retry in lockstep
    retryPolicy = RetryPolicy.Exponential(
        maxAttempts = 3,
        initialDelay = 200.milliseconds,
        maxDelay = 2.seconds,
        multiplier = 2.0,
        jitterRatio = 0.2,
    ),
)
```

Shortcuts: `RetryPolicy.aggressive()` is 5 attempts from 250 ms; `RetryPolicy.fixed(n, ms)`
retries every `ms`; `RetryPolicy.Exponential(...)` takes the full schedule. Jitter scales
the delay into `[1 - jitterRatio, 1]`.

> The `-ktx` Lifecycle artifacts became empty aliases in 2.9. `viewModelScope` and
> `AndroidViewModel` now live in `androidx.lifecycle:lifecycle-viewmodel`, which is what
> `:cache-android` depends on.

### 10. Bounding how much L2 keeps

```kotlin
val cache = L2KmpCache(
    storageEngine = FileStorageEngine(cacheDir),
    valueSerializer = KotlinxJsonSerializer(),
    maxSize = 500,                            // null = no limit; 0 or below also means no limit
    evictionStrategy = EvictionStrategy.LRU,
    autoReapEvery = 10.minutes,               // null = never delete expired records on a schedule
)
```

Three independent policies, all off by default:

| Parameter | Default | What it does |
|---|---|---|
| `maxSize` | `null` | Keeps at most this many records, dropping the worst candidate on each write that exceeds it. Measured in records, not bytes. |
| `evictionStrategy` | `EvictionStrategy.LRU` | Which record `maxSize` drops: `LRU`, `FIFO`, `MRU` or `LIFO`. |
| `autoReapEvery` | `null` | Deletes expired records on an interval. Without it an expired record stays until its key is read or overwritten. |

**Expired records always go first**, whatever the strategy says. An expired record cannot be
served by any read path, so dropping it costs nothing; dropping a fresh one does.

**Access tracking follows the limit, not a separate switch.** `LRU` and `MRU` rank by
`lastAccessedAt`, which a read refreshes at most once per `touchGranularity` (one minute by
default). That floor is what keeps a key read in a loop from costing a write per read. But
without a `maxSize` nothing would ever be evicted, so nothing would ever read those timestamps
back — a cache that was never asked to bound its size therefore performs **no extra writes at
all**.

```kotlin
// Exact recency, at the cost of a write on every single read. Rarely what you want.
L2KmpCache(engine, serializer, maxSize = 100, touchGranularity = Duration.ZERO)
```

Both caches accept the same parameters, so `ChainKmpCache` bounds L2 while L1 stays bounded by
`L1MemoryCache(maxSize = …)`. `autoReapEvery` on a `ChainKmpCache` also sweeps L1.

`autoReapEvery` starts a coroutine that lives as long as the cache, so a cache configured this
way is expected to be long-lived — an application-scoped one. Call `stopAutoReap()` when a cache
outlives its owner, or when the sweeping should stop while the cache stays usable.

### 11. Testing TTL without waiting

`TimeSource` is injectable, so expiry is deterministic and no test ever sleeps.

```kotlin
import io.github.slavikjunior.kache.core.MutableTimeSource
import kotlin.time.Duration.Companion.milliseconds

val clock = MutableTimeSource(initialTimeMillis = 0L)
val cache = L1MemoryCache<String, String>(maxSize = 10, timeSource = clock)

cache.put("k", "v", ttl = 100.milliseconds)
clock.advance(101.milliseconds)

assertNull(cache.get("k"))
```

### 12. Observing the cache

`KacheLogger` receives what the cache did, never what it stored, and every method has a no-op
default, so an implementation overrides only what it needs.

```kotlin
import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.KacheLogger

class HitRatio : KacheLogger {
    var hits = 0
        private set
    var misses = 0
        private set

    override fun onHit(key: String, origin: CacheOrigin) {
        hits++
    }

    override fun onMiss(key: String, strategy: CacheStrategy) {
        misses++
    }
}

val cache = L2KmpCache<String, UserProfile>(
    storageEngine = engine,
    valueSerializer = KotlinxJsonSerializer(UserProfile.serializer()),
    logger = HitRatio(),
)
```

Events are reported inline on the read path, so an implementation must be thread-safe and cheap.
Keys arrive already converted through `keyToString`: a sensitive key is redacted where the cache
is configured, not inside every implementation. Statistics are counted from these events rather
than provided by the library, which leaves the numbers — and their shape — to the application.

`onEvict` fires only when a record is dropped to honour `maxSize`. A record removed by a reap was
already expired, so it is not an eviction: nothing was given up to make room.

---

## Which backend should I use?

| You want | Use |
|---|---|
| Memory only, or a shared UI cache | `L1MemoryCache` |
| Fast local disk, one file per key, no schema | `:cache-storage` (`FileStorageEngine`) |
| Indexed storage, migrations, or the only option on iOS | `:cache-store-room` (Room) |
| Memory in front of disk | `ChainKmpCache` + any `StorageEngine` |

### Implementing your own backend

`StorageEngine` has **five required** and **three optional** members. The optional ones have
defaults that keep a backend working, but each default silently disables a feature — so read
this table before deciding to skip one.

| Member | Required | Default if omitted | What you lose without it |
|---|---|---|---|
| `get(key)` | yes | — | — |
| `put(key, record)` | yes | — | — |
| `remove(key)` | yes | — | — |
| `clear()` | yes | — | — |
| `size()` | yes | — | — |
| `removeExpired(now)` | no | returns `0` | Expired records are **never** deleted, so a cache grows without bound. Nothing calls it on a schedule for you. |
| `touch(key, accessedAt)` | no | returns `false` | `lastAccessedAt` never changes, so `EvictionStrategy.LRU` and `MRU` silently behave like `FIFO`. |
| `evictionCandidates(strategy, limit, now)` | no | returns an empty list | `maxSize` **silently does nothing**: the cache stays over its limit and no warning is issued. |

The last row is the dangerous one. A backend with only the five required functions looks
entirely healthy while `maxSize` does nothing at all. If you set `maxSize`, implement
`evictionCandidates`; if you use `LRU` or `MRU`, implement `touch` as well.

`evictionCandidates` must return keys that are valid inputs to `remove`, worst candidate
first, with expired records ahead of whatever the strategy ranks — see
[recipe 10](#10-bounding-how-much-l2-keeps) for the ordering the library relies on.

## Security considerations

A cache holds whatever an app decided was worth keeping, which is often worth protecting.
What this library does and does not do about that.

**Cached data is not encrypted.** A payload is written exactly as the serializer produced it.
If an app caches tokens or personal data, that data is readable by anything that can read the
file — including a device backup. The library offers no encryption; wrap the serializer if you
need it, or do not cache secrets.

**Where the data lands is the app's call, and the defaults differ per backend.** Room and
`FileStorageEngine`, both through `createFromContext`, use the app's **cache** directory, which
Android excludes from Auto Backup and may purge at any time. iOS's `createFromCachesDirectory`
uses `NSCachesDirectory` for the same reason. The JVM factories take a path you supply, so a
backed-up or shared location is possible — deliberately, but then it is your decision.

**Keys end up on disk.** The file backend encodes the key into the file name, and the encoding
is reversible, so a key can be recovered from a directory listing. Keys are identifiers, not
secrets: do not put a token, an e-mail address or a session id in one. Distinct keys always map
to distinct files, so two keys can never collide onto one record and be served each other's
value.

**Set `maxSize` for anything that outlives a screen.** An unbounded cache grows until the
device runs out of space, and expired records are only removed when a key is read or
overwritten. Both factories default to unbounded on purpose — bounding also turns on access
tracking, which costs a write per key per touch window — so the limit is yours to pass.

**Records are not authenticated.** Nothing detects a cached value edited on disk, and the
timestamps a record carries decide when it expires, so anyone able to write to the cache
directory can keep a modified value alive. Treat the cache directory as untrusted: the library
does, and a record it cannot parse becomes a miss rather than an error.

**Untrusted input is bounded.** A record over `maxRecordBytes` (16 MiB by default) is refused
on write and never loaded on read, and a record that fails to decode is dropped instead of
being served or thrown.

**No network, no logging, no telemetry.** The library performs no I/O beyond the storage
backend you hand it, writes nothing to the log, and sends nothing anywhere.

To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Dependencies this library brings in

| Module | Transitive dependencies |
|---|---|
| `:cache-core` | `kotlinx-coroutines-core` |
| `:cache-storage` | the above + `kotlinx-serialization-json` |
| `:cache-store-room` | the above + `androidx.room`, `androidx.sqlite` |
| `:cache-android` | the above + `androidx.lifecycle:lifecycle-viewmodel` |

No UI toolkit, no DI framework, no logging facade. `:cache-android` deliberately does not
pull Room or WorkManager.

## Architecture

```
        ┌──────────────────────────────┐
        │  :cache-core   (commonMain)  │
        │                              │
        │  KmpCache   ← the contract   │
        │  CacheStrategy               │
        │  CacheResult / CacheOrigin   │
        │  RetryPolicy                 │
        │  L1MemoryCache               │
        │  EvictionStrategy            │
        │  StorageEngine  ← the SPI    │
        │  KacheStateHolder            │
        └───────┬──────────────┬───────┘
                │              │
   ┌────────────▼──────┐  ┌────▼──────────────┐
   │  :cache-storage   │  │ :cache-store-room │
   │  FileStorageEngine│  │ RoomStorageEngine │
   │  jvm/android/ios   │  │ jvm/android/ios   │
   └───────────────────┘  └───────────────────┘

        ┌──────────────────────────────┐
        │  :cache-android  (android)   │
        │  l2Cache, KacheableViewModel │
        │  clearWhenScopeCancelled     │
        │  memory-pressure callbacks   │
        └──────────────────────────────┘
```

`StorageEngine` is the extension point. Five members are required; `removeExpired`, `touch`
and `evictionCandidates` have defaults that keep a backend working but silently disable
reaping, LRU ordering and `maxSize` respectively — see
[Implementing your own backend](#implementing-your-own-backend) before omitting any of them.

`StorageRecord` carries `lastAccessedAt` alongside `createdAt` and `ttl`. The access timestamp
exists for [EvictionStrategy](#10-bounding-how-much-l2-keeps): `LRU` and `MRU` rank by it, and
it is only refreshed when a cache has a `maxSize` to begin with.

`:cache-core` owns policy and depends on nothing but coroutines. The backends depend on it,
never on each other. `:cache-android` is a convenience layer and adds no storage.

`FileStorageEngine` is common code with three platform file-system implementations behind it:
`java.io` on JVM and Android, kotlinx-io on iOS. The split is not a preference — kotlinx-io's
JVM file system is built on `java.nio.file`, which needs Android API 26, and the library
supports 23. The engine's algorithm, key encoding and the temp-then-move protocol are shared,
so a record written by any platform reads back on any other.

## Building from source

```bash
./gradlew check                    # tests: JVM, Android host, iOS simulator
./gradlew apiCheck                 # binary-compatibility check
./gradlew :sample-android:assembleDebug
./gradlew :sample:run                      # desktop
./gradlew verifyLocalPublication   # publishes locally and checks the artifact set
```

The sample runs on three platforms from one `commonMain`: Android through
`:sample-android`, the desktop through `:sample:run`, and iOS through the Xcode project in
`iosApp/` (`cd iosApp && xcodegen generate`, then build the `iosApp` scheme). Its Android
half is built and linted by `:sample-android`; the iOS framework is linked by
`:sample:linkDebugFrameworkIosSimulatorArm64`.

`verifyLocalPublication` is the gate that publishing itself does not provide: Gradle
happily reports success for a publication with no `-sources.jar`, while Maven Central
rejects one. The task checks every publication for a POM, a module descriptor, non-empty
sources and javadoc, and an AAR or klib where the target needs one. It also fails when a
publication disappears entirely, so a dropped target cannot go unnoticed.

The instrumented Room suite runs on a device or emulator, which is the only place the
bundled SQLite driver can load:

```bash
./gradlew :cache-store-room:connectedAndroidDeviceTest
```

`verification/` contains three throwaway builds that consume Kache the way a real user does —
as coordinates resolved from `build/repo`, never as `project(...)`. Unit tests inside the
build cannot cross that boundary; a wrong coordinate, a POM missing a field or a hidden
`api` dependency only shows up here.

```bash
./gradlew -p verification/consumer run                      # JVM
./gradlew -p verification/consumer-android assembleDebug    # plain Android, AAR variants
./gradlew -p verification/consumer-kmp :app:compileKotlinJvm :app:compileKotlinIosArm64
./gradlew -p verification/consumer-kmp :iosx64:compileKotlinIosX64
```

`consumer-android` and `consumer-kmp` declare no serialization dependency of their own, so
they fail to compile the moment `:cache-storage` stops exposing `KSerializer` as `api`.

Every build input — jar, klib, and `.pom`/`.module` descriptor — is checked against a recorded
SHA-256 in `gradle/verification-metadata.xml`, so a dependency bump fails until the metadata is
regenerated:

```bash
./gradlew --write-verification-metadata sha256 check :sample-android:assembleDebug
./gradlew --dependency-verification=strict check
```

Documentation artifacts are the deliberate exception. `-sources.jar`, `-javadoc.jar` and their
`-samples-sources` / `-common-sources` variants are trusted by name through `<trusted-artifacts>`:
they never reach a compile or runtime classpath, and pinning them was the one part of this file
that could not converge — `--write-verification-metadata` records only what the invoking process
downloads, while the IDE resolves documentation variants that no CLI task touches. Binaries,
klibs and descriptors stay pinned; the trade is stated in the file next to the rules.

Two events still need an explicit command, and both are rare:

- **A Gradle wrapper upgrade.** The distribution embeds its own runtime libraries in
  `gradle.gradleHomeDir/lib`, and the IDE resolves their aliases in order to index them, so their
  descriptors must be pinned even though no CLI task resolves them:

  ```bash
  ./gradlew --write-verification-metadata sha256 recordIdeIndexingArtifacts
  ```

  The task reads the coordinates the distribution itself writes next to each library in
  `lib/*.properties` (`alias.group`/`alias.name`/`alias.version`) instead of hardcoding them, so a
  wrapper upgrade is covered as a class. Documentation files themselves are trusted and need no
  entry, which is what keeps this a single step.
- **An AGP upgrade.** `aapt2` is chosen by an artifact transform per OS, so every platform you
  build on needs its own checksum: pinning only the host you generated the file on made the Linux
  CI job fail while macOS stayed green. All three variants (`-linux`, `-osx`, `-windows`) are
  recorded; compute the new values from the artifacts published in
  `dl.google.com/android/maven2`.

Regenerate the whole file after a dependency bump rather than patching it, and prove the result
with `--refresh-dependencies` — without it, verification passes against the cache and proves
nothing.

iOS compilation and linking must be requested explicitly, because `./gradlew check` does not
prove them — `iosX64Test` is disabled on Apple Silicon and `iosArm64Test` needs a physical
device:

```bash
./gradlew :cache-core:compileKotlinIosArm64 \
          :cache-core:compileKotlinIosSimulatorArm64 \
          :cache-core:compileKotlinIosX64 \
          :cache-core:iosArm64MainBinaries \
          :cache-core:iosSimulatorArm64MainBinaries \
          :cache-core:iosX64MainBinaries
```

## Project status

`0.1.0`. Nine roadmap phases are defined; eight are implemented and the ninth
(observability) is deliberately not started. Capacity limits, eviction strategies and
optional expired-record reaping shipped alongside them, and a thirteen-finding security audit
is closed out (see [`SECURITY.md`](SECURITY.md) and [`docs/security-audit.md`](docs/security-audit.md)).
Module layering, data flow and the release specification are documented in
[`docs/`](docs/ARCHITECTURE.md), and the release history is in [`CHANGELOG.md`](CHANGELOG.md).

**Test coverage** — 743 tests in the standard matrix, plus a 20-test instrumented suite, green
on every host:

| Module | JVM | Android host | iOS simulator | Android device |
|---|---:|---:|---:|---:|
| `:cache-core` | 120 | 120 | 120 | — |
| `:cache-storage` | 66 | — | — | — |
| `:cache-store-room` | 23 | 11 | 20 | 20 |
| `:cache-android` | — | 41 | — | — |

`:cache-core` runs one set of `commonTest` sources on all three hosts, so its count is identical
by construction. `:cache-store-room` has a single shared contract (`RoomStorageEngineTestBase`)
with thin per-platform subclasses supplying only a factory, so the device suite runs the same
contract as the host and iOS ones.

**Known gaps, stated plainly:**

- The **Android public API is not covered by binary-compatibility validation.** BCV hooks a
  KMP Android target only when its compilation is named `release`, while the AGP KMP plugin
  names it `main` (checked up to BCV 0.18.1), so `:cache-android` publishes an AAR with no
  golden API file. The gap is mitigated by a reflection-based `PublicApiTest` that pins the
  public surface, not closed.
- `createFromContext` — the Room factory consumers actually call — is covered on a real
  device by `RoomStorageEngineAndroidDeviceTest`, which loads the bundled SQLite driver for
  real. The host suite cannot do that: the driver's JNI library is built for Android ABIs.
- **Schema version 2 is a breaking change for an existing Room database.** `:cache-store-room`
  went from schema 1 to 2 to add `last_accessed_at`. A migration is provided and wired into every
  factory, so existing rows are carried forward and backfilled from `created_at` — they rank by
  write time until they are read again. Backfilling rather than leaving the column default keeps
  an upgraded cache from treating every pre-existing row as the least recently used one and
  throwing the lot away on the first eviction.
- **An existing file cache survives the header change.** The on-disk record gained a field, so
  the decoder now reads two layouts. It tells them apart by a leading version marker rather than
  by counting separators, which cannot work: the payload is arbitrary binary that may itself
  contain the separator byte. A legacy record is read with its access time set to its creation
  time — the same backfill the database migration performs — so records written by either layout
  rank identically until they are read again. A file from a *future* layout, or from a different
  format entirely, is discarded rather than guessed at, since a cache directory is disposable.
- **An existing file cache is not read after an upgrade.** The security audit replaced the
  key → filename mapping, because the old one was lossy and could not be inverted, so two
  different keys could collide onto one record. Old files are neither read nor migrated; they
  are dead weight in the cache directory until it is cleared. That is the correct trade for a
  cache, but it is a one-way door.
- **`KacheException.ExpiredException` was removed.** Nothing ever threw it, and it was the one
  exception that put a caller-supplied key into `message` and `toString`. An expired record is
  a miss, not an error. A consumer that caught it will not compile.
- Published to Maven Central as `0.1.0`. The release procedure, including the steps that are easy
  to get wrong, is documented in [`docs/RELEASING.md`](docs/RELEASING.md).

## License

MIT. See [LICENSE](LICENSE).