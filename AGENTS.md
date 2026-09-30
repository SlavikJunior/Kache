# Agent Directives & Project Rules — Kache (KMP Caching Framework)

Welcome, AI Agent! You are helping build **Kache**, an enterprise-grade Kotlin Multiplatform (KMP) & Android caching library.

## Core Rules & Constraints

1. **KMP Compatibility:**
   - Pure Kotlin in `:cache-core` (`commonMain`). 
   - Strict avoidance of JVM/Android specific classes (`java.io.*`, `android.os.*`, `synchronized`, `@Volatile`) inside `commonMain`.
   - Use `kotlinx.coroutines` and `kotlinx.atomicfu` for thread-safety across Native (iOS), JVM, and Android.

2. **Error Handling & Typed Exceptions:**
   - Never expose raw, unmapped exceptions to the user. Wrap low-level failures into `KacheException` hierarchy (`NetworkException`, `CacheReadException`, `CacheWriteException`, `CacheExpiredException`).

3. **Development Workflow (Spec-Driven & TDD):**
   - Read specifications in `docs/` before implementing any feature.
   - Always write or update tests in `commonTest` alongside implementation.
   - Run `./gradlew check` to ensure all targets (Android, JVM, iOS) compile and pass tests.

4. **Code Quality:**
   - Maintain SOLID architecture with pluggable storage backends.
   - Document public API using KDoc.
   - Keep classes modular, concise, and focused on single responsibilities.

## Project Structure
- `:cache-core` (KMP core abstractions, policies, L1 memory cache)
- `:cache-store-room` (L2 persistent storage adapter powered by Room KMP)
- `:cache-android` (Android KTX, Lifecycle adapters, WorkManager sync)