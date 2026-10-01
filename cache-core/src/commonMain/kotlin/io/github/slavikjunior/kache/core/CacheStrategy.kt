package io.github.slavikjunior.kache.core

/** Defines how a cache operation balances cached and network data. */
public enum class CacheStrategy {
    /** Read from cache first and use the network only on a miss. */
    CacheFirst,

    /** Prefer fresh network data and fall back to cache on failure. */
    NetworkFirst,

    /** Emit cached data and then refresh it from the network. */
    CacheAndNetwork,

    /** Return cached data immediately and refresh it in the background when needed. */
    StaleWhileRevalidate,
}
