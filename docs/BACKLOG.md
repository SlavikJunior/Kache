# Kache Project Backlog

## Phase 1: Core Foundation & API Contract — DONE
- [x] Initial Gradle multi-project setup (`:cache-core`)
- [x] `CacheOrigin`, `CacheStrategy`, `CacheResult` models
- [x] `KacheException` hierarchy
- [x] Core `KmpCache<K, V>` and `StorageEngine` interfaces (the spec called the latter `CacheStore`; renamed to reflect that it only stores)
- [x] Unit tests for data models and state emissions in `commonTest`

## Phase 2: L1 In-Memory Caching Engine — DONE
- [x] Thread-safe LRU memory cache for KMP (`L1MemoryCache`, `Mutex`-based)
- [x] TTL handling with injectable `TimeSource`
- [x] `getStale` for strategies that tolerate expired data
- [x] `removeExpired` reaper for entries that are never read again
- [x] Unit tests for eviction, TTL and interleaved access
- [ ] Tag-based invalidation (`invalidateByTag`) — not implemented, deliberately descoped: no tier currently carries tags and no caller needs it yet

## Phase 3: Strategy Pipeline — DONE (moved up from the original plan)
- [x] `CachePipeline`: one implementation of all four strategies
- [x] `CacheTier` + `ChainTier` (L1+L2) + `StorageTier` (L2 only)
- [x] `ChainKmpCache` and `L2KmpCache` as thin facades over the pipeline
- [x] `RetryPolicy` wired into fetch, cancellation-safe

## Phase 4: L2 Persistent Storage — PARTIAL
- [x] `:cache-storage` module with `FileStorageEngine` (atomic writes, corrupt records dropped)
- [x] `StorageRecordFileCodec` in `commonMain`, one file format for all platforms
- [x] `StringSerializer` / `KotlinxJsonSerializer` (expect/actual)
- [ ] `:cache-store-room` module (Room KMP) — the module named in AGENTS.md is not created; `:cache-storage` currently plays this role for files
- [ ] Persistent L2 for iOS — today iOS only gets the L1 tier, see ARCHITECTURE.md
- [ ] `removeExpired` reaper for persistent backends

## Phase 5: Android KTX & Integrations
- [ ] Create `:cache-android` module
- [ ] Add `ViewModel` scope extensions
- [ ] Implement `Context` auto-initializers
- [ ] WorkManager background sync adapter

## Phase 6: Library Readiness (must precede any release)
- [ ] `api` instead of `implementation` for coroutines and `:cache-core` — `Flow` and `KmpCache` are in the public API, so consumers currently cannot resolve them transitively
- [ ] `explicitApi()` in both modules
- [ ] Maven Publish configuration, POM metadata, license headers
- [ ] Dokka site generation
- [ ] README with a runnable usage example
- [ ] Binary compatibility validator

## Phase 7: Documentation & AI SDLC Artifacts
- [x] Keep specs, `ARCHITECTURE.md` and this backlog in sync with the code
- [ ] Record AI vs Human engineering metrics
- [ ] Draft article and conference talk outline

## Test Status
`./gradlew check` is green. `:cache-core` runs 83 tests on JVM, Android host and the iOS simulator; `:cache-storage` runs 23 JVM tests plus 7 on the Android host.