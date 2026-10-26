package io.github.slavikjunior.kache.sample.di

import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventLog
import io.github.slavikjunior.kache.sample.data.remote.NhtsaApi
import io.github.slavikjunior.kache.sample.data.remote.dto.CanadianSpecsDto
import io.github.slavikjunior.kache.sample.data.remote.dto.MakeDto
import io.github.slavikjunior.kache.sample.data.remote.dto.ModelDto
import io.github.slavikjunior.kache.sample.data.repository.DefaultCarRepository
import io.github.slavikjunior.kache.store.room.createFromCachesDirectory
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.serialization.builtins.ListSerializer

/**
 * iOS composition root.
 *
 * There is no file-backed engine on iOS (`java.io` is a JVM API), so the durable tier is
 * `:cache-store-room`: a Room database on bundled SQLite, placed in the Caches directory —
 * iOS may purge that directory under disk pressure, which is exactly the lifetime a cache
 * wants. Each cache gets its own database file, because `maxSize` is enforced per engine
 * and a shared engine would evict one screen's records to make room for another's.
 */
fun createAppContainer(): AppContainer {
    val log = CacheEventLog()

    val client = HttpClient(Darwin) {
        installCarClientDefaults()
    }

    val repository = DefaultCarRepository(
        remote = NhtsaApi(client),
        makesCache = carCache(
            engine = createFromCachesDirectory("${CacheNames.MAKES}.db"),
            serializer = ListSerializer(MakeDto.serializer()),
            ttl = CacheTtls.MAKES,
            log = log,
        ),
        modelsCache = carCache(
            engine = createFromCachesDirectory("${CacheNames.MODELS}.db"),
            serializer = ListSerializer(ModelDto.serializer()),
            ttl = CacheTtls.MODELS,
            log = log,
            retryPolicy = MODELS_RETRY,
            maxSize = CacheLimits.MODELS_RECORDS,
        ),
        specsCache = carCache(
            engine = createFromCachesDirectory("${CacheNames.SPECS}.db"),
            serializer = ListSerializer(CanadianSpecsDto.serializer()),
            ttl = CacheTtls.SPECS,
            log = log,
            maxSize = CacheLimits.SPECS_RECORDS,
        ),
    )

    return AppContainer(cacheLog = log, carRepository = repository)
}
