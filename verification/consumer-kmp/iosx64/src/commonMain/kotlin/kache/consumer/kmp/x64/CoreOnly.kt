package kache.consumer.kmp.x64

import io.github.slavikjunior.kache.core.L1MemoryCache

/** Compiled for `iosX64` as well as `jvm`, proving both published variants resolve. */
fun memoryCache() = L1MemoryCache<String, String>(maxSize = 10)
