# Kotlin Multiplatform consumer verification harness

A throwaway KMP build that consumes Kache the way a multiplatform project would: as external
Maven coordinates resolved from a repository, with no access to the sources.

## Running it

Publish locally first, then build this project with the root wrapper:

```bash
./gradlew publishAllPublicationsToLocalRepository

# `:app` — jvm, android, iosArm64, iosSimulatorArm64
./gradlew -p verification/consumer-kmp :app:compileKotlinJvm
./gradlew -p verification/consumer-kmp :app:compileAndroidMain
./gradlew -p verification/consumer-kmp :app:compileKotlinIosArm64
./gradlew -p verification/consumer-kmp :app:compileKotlinIosSimulatorArm64

# `:iosx64` — the `iosX64` variant of `cache-core` alone
./gradlew -p verification/consumer-kmp :iosx64:compileKotlinIosX64
```

The repository path defaults to `../../build/repo`. Override it with
`-Pkache.localRepo=/some/other/repo`. Building the Android target needs a valid Android SDK
(`ANDROID_HOME` or `sdk.dir` in `local.properties`), the iOS targets need Xcode.

## What it checks

1. **Metadata variants** — `commonMain` compiles against `cache-core` and `cache-store-room`
   for every declared target, so the root modules and their `available-at` redirects have to
   be complete.
2. **Source-set placement** — `cache-storage` sits in `commonMain`, because it publishes a
   variant for every target this build declares. That reverses its pre-1.0 state, when it was
   jvm/android-only: the iOS klib now carries `KotlinxJsonSerializer` and `FileStorageEngine`.
   `commonSerializer` in `commonMain` proves the serializer half compiles from common code.
3. **Platform entry points** — `createFromFile` (JVM), `createFromContext` (Android, from
   both `:cache-storage` and `:cache-store-room`), `createFromCachesDirectory` (iOS).
4. **`iosX64` coverage** — `:iosx64` proves the one variant `:cache-store-room` does not
   publish is not needed to consume `cache-core` on that target.
