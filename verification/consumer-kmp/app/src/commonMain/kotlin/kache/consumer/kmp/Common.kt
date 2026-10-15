package io.github.slavikjunior.kache.consumer.kmp

import io.github.slavikjunior.kache.core.ChainKmpCache
import io.github.slavikjunior.kache.core.KacheSerializer
import io.github.slavikjunior.kache.core.L1MemoryCache
import io.github.slavikjunior.kache.core.StorageEngine
import kotlin.time.Duration.Companion.minutes

/**
 * `commonMain` code over the published metadata variant.
 *
 * This compiles only if `cache-core`'s root module really carries a common variant with
 * `ChainKmpCache`, `L1MemoryCache` and `StorageEngine` for every target the module declares.
 * A missing or misnamed variant fails here, not in a comment.
 */
fun <V : Any> twoTierCache(engine: StorageEngine, serializer: KacheSerializer<V>) =
    ChainKmpCache<String, V>(
        l1Cache = L1MemoryCache(maxSize = 50, defaultTtl = 5.minutes),
        l2Storage = engine,
        serializer = serializer,
    )
