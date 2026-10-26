package io.github.slavikjunior.kache.sample.di

import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventLog
import io.github.slavikjunior.kache.sample.data.remote.NhtsaApi
import io.github.slavikjunior.kache.sample.data.remote.dto.CanadianSpecsDto
import io.github.slavikjunior.kache.sample.data.remote.dto.MakeDto
import io.github.slavikjunior.kache.sample.data.remote.dto.ModelDto
import io.github.slavikjunior.kache.sample.data.repository.DefaultCarRepository
import io.github.slavikjunior.kache.storage.FileStorageEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Desktop composition root.
 *
 * The desktop has no `Context`, so the storage directory is chosen here: a per-user
 * subdirectory of the JVM temporary directory. It survives a restart — which is what makes
 * the `cache · disk` badge meaningful on a relaunch — and the OS is free to purge it, which
 * is the correct lifetime for a cache.
 */
fun createAppContainer(): AppContainer {
    val log = CacheEventLog()
    val root = File(System.getProperty("java.io.tmpdir"), "kache-sample")

    val client = HttpClient(CIO) {
        installCarClientDefaults()
    }

    val repository = DefaultCarRepository(
        remote = NhtsaApi(client),
        makesCache = carCache(
            engine = FileStorageEngine(File(root, CacheNames.MAKES).absolutePath),
            serializer = ListSerializer(MakeDto.serializer()),
            ttl = CacheTtls.MAKES,
            log = log,
        ),
        modelsCache = carCache(
            engine = FileStorageEngine(File(root, CacheNames.MODELS).absolutePath),
            serializer = ListSerializer(ModelDto.serializer()),
            ttl = CacheTtls.MODELS,
            log = log,
            retryPolicy = MODELS_RETRY,
            maxSize = CacheLimits.MODELS_RECORDS,
        ),
        specsCache = carCache(
            engine = FileStorageEngine(File(root, CacheNames.SPECS).absolutePath),
            serializer = ListSerializer(CanadianSpecsDto.serializer()),
            ttl = CacheTtls.SPECS,
            log = log,
            maxSize = CacheLimits.SPECS_RECORDS,
        ),
    )

    return AppContainer(cacheLog = log, carRepository = repository)
}
