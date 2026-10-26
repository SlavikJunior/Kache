# Consumer verification harness

A throwaway project that consumes Kache the way a real user would: as an external Maven
dependency resolved from a repository, with no access to the sources.

Unit tests run against source sets inside the build. They cannot catch the class of failure
that only appears at the boundary a consumer actually crosses — a wrong coordinate, a POM
missing a required field, an absent `sources` or `javadoc` artifact, a variant published under
the wrong name. This project exists to cross that boundary.

## Why it is a separate Gradle build

`settings.gradle.kts` makes it independent of the root build, and the dependency is declared as
a plain coordinate:

```kotlin
implementation("io.github.slavikjunior.kache:cache-core:1.0.0")
```

There is deliberately no `project(":cache-core")` anywhere. Wiring the sources in directly would
defeat the whole point — the build would resolve classes from the module rather than from the
artifacts, so every packaging mistake would pass unnoticed.

## Running it

Publish locally first, then run:

```bash
./gradlew publishAllPublicationsToLocalRepository
cd verification/consumer && ./gradlew run
```

The repository path defaults to `../../build/repo`. Override it with
`-Pkache.localRepo=/some/other/repo` to point at a different one.

## What it checks

1. **Persistence** — writes through one engine, reads through a second engine over the same
   directory, which is what a process restart looks like from the library's point of view.
2. **Eviction strategies** — `LRU`, `FIFO`, `MRU` and `LIFO` each drop the key they are supposed
   to, and none of them drops the key that triggered the eviction.

   This uses an injected `MutableTimeSource`. On the system clock the three writes and the read
   land inside the same millisecond, every access timestamp ties, the sort has nothing to
   distinguish and all four strategies appear identical — a test that passes without testing
   anything. The harness caught that: the first version of it printed four identical results.
3. **Capacity** — 50 writes against a limit of 5 leave exactly 5 records; the same engine with no
   limit keeps everything.
4. **Read strategies and failure handling** — a cold `CacheFirst` goes to the fetcher, the second
   read is served from disk without calling the fetcher again, an expired record is not served,
   and a corrupt file produces a miss rather than an exception.
5. **Key properties** — distinct keys get distinct files and each returns its own value, the
   keys `"."` and `".."` stay inside the cache directory, and a parent directory is left
   untouched.

   These exist because the unit tests cannot see them: traversal is a property of what the
   filesystem does with the resolved path, so it needs a real one. This is the only check in
   the harness that would have caught the lossy key encoding (F3) on an *upgrade* rather than
   on a fresh directory — the two keys it distinguishes are the ones that used to collide.

## Dependency verification

This is a separate Gradle build, so it does **not** inherit the root build's
`gradle/verification-metadata.xml` — the locally published Kache artifacts would fail it,
since their checksums change on every local publication. What the harness proves instead is
that the artifacts are *resolvable and compilable* as coordinates; checksums of the
third-party dependencies stay the root build's concern.

It declares no `kotlinx-serialization` dependency of its own, so it also fails to compile the
moment `:cache-storage` stops exposing `KSerializer` as `api`.

## In CI

The `publication` job publishes to the local repository and then runs this harness, so an
artifact set that cannot be consumed fails the build instead of reaching a release.