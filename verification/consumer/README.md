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
implementation("io.github.slavikjunior.kache:cache-core:0.1.0")
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

## In CI

The `publication` job publishes to the local repository and then runs this harness, so an
artifact set that cannot be consumed fails the build instead of reaching a release.