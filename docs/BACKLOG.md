# Kache Project Backlog

## Phase 1: Core Foundation & API Contract (Current)
- [ ] Initial Gradle multi-project setup (`:cache-core`)
- [ ] Define `CacheOrigin`, `CacheStrategy`, and `CacheResult` model
- [ ] Implement `KacheException` hierarchy
- [ ] Define core `KmpCache<K, V>` and `CacheStore<K, V>` interfaces
- [ ] Write unit tests for data models and state emissions in `commonTest`

## Phase 2: L1 In-Memory Caching Engine
- [ ] Implement thread-safe LRU Memory Cache for KMP (`L1MemoryCache`)
- [ ] Implement TTL (Time-To-Live) evictor policy
- [ ] Tag-based invalidation (`invalidateByTag`)
- [ ] Unit tests for L1 memory eviction and thread safety

## Phase 3: L2 Persistent Storage (Room KMP)
- [ ] Create `:cache-store-room` module
- [ ] Setup Room KMP entity and DAO for key-value entry metadata
- [ ] Implement `RoomCacheStore` adapting `CacheStore`
- [ ] Test Room integration across JVM, Android, and iOS targets

## Phase 4: Android KTX & Integrations
- [ ] Create `:cache-android` module
- [ ] Add `ViewModel` scope extensions
- [ ] Implement `Context` auto-initializers
- [ ] WorkManager background sync adapter

## Phase 5: Documentation & AI SDLC Artifacts
- [ ] Generate KotlinDoc site
- [ ] Record AI vs Human engineering metrics
- [ ] Draft article and conference talk outline