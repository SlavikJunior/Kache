# Kache Architecture Overview

## Overview
**Kache** is a multi-tiered, reactive Kotlin Multiplatform caching library designed to bridge Network, Memory (L1), and Disk (L2) layers with a single declarative API.

## Core Data Flow

[ UI / Presenter / ViewModel ]
│
get(key, strategy)
▼
[ Kache Pipeline ]
┌────┴──────────────────────────┐
▼                               ▼
┌───────────┐                 ┌──────────────┐
│ L1 Cache  │ ── (Miss) ────► │  L2 Cache    │
│ (Memory)  │ ◄─ (Promote) ── │ (Room/Disk)  │
└───────────┘                 └──────────────┘
│                               │
(Miss)                          (Miss)
└───────────────┬───────────────┘
▼
[ Network / Fetcher ]

## Core Abstractions

### 1. States & Results (`CacheResult<T>`)
Every stream operation returns `Flow<CacheResult<T>>`:
- `Success(data: T, origin: CacheOrigin)`
- `Loading(cachedData: T?)` — emits stale data while fetching fresh data.
- `Error(error: KacheException, cachedData: T?)` — provides fallback data alongside domain errors.

### 2. Typed Exceptions (`KacheException`)
Extends `Exception`:
- `KacheException.Network(cause: Throwable)`
- `KacheException.DiskRead(cause: Throwable)`
- `KacheException.DiskWrite(cause: Throwable)`
- `KacheException.Serialization(cause: Throwable)`
- `KacheException.Expired(key: Any)`

### 3. Fetch Strategies (`CacheStrategy`)
- `CacheFirst`: Check L1 -> L2 -> Fetch Network.
- `NetworkFirst`: Fetch Network -> Fallback to L1/L2 on error.
- `CacheAndNetwork`: Immediately emit L1/L2 cached data, then refresh from Network.
- `StaleWhileRevalidate`: Return cached value instantly; trigger background update if TTL expired.