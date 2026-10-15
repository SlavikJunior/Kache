# Android consumer verification harness

A throwaway Android library that consumes Kache the way a plain (non-KMP) Android project
would: as external Maven coordinates resolved from a repository, with no access to the
sources.

`verification/consumer` proves the JVM artifacts are consumable. This module proves the
Android ones are: the AAR variants of `cache-core`, `cache-storage`, `cache-store-room` and
`cache-android`, plus the public API that only exists on Android (`l2Cache`, the Room
`Context` factory, `KacheableViewModel`).

## Running it

Publish locally first, then build this module with the root wrapper:

```bash
./gradlew publishAllPublicationsToLocalRepository
./gradlew -p verification/consumer-android assembleDebug
```

The repository path defaults to `../../build/repo`. Override it with
`-Pkache.localRepo=/some/other/repo`.

## What it checks

1. **Variant resolution** — a plain `com.android.library` project resolves the AAR of every
   Kache module, not the Kotlin/Native klibs and not the root metadata module.
2. **`api` scopes** — the module declares no `kotlinx-serialization` dependency, yet calls
   `Profile.serializer()` and `KotlinxJsonSerializer(...)`. That only compiles while
   `cache-storage` exposes serialization as `api`.
3. **Android-only API** — `l2Cache(context, …)`, `createFromContext(context, …)` from
   `:cache-store-room`, and `KacheableViewModel` are all references a consumer would write.

Nothing is executed: the gate is `assembleDebug`, which fails before packaging if any of the
above stops being true.
