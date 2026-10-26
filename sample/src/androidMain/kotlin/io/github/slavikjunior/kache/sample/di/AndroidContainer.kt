package io.github.slavikjunior.kache.sample.di

import android.content.Context
import io.github.slavikjunior.kache.android.l2Cache
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventLog
import io.github.slavikjunior.kache.sample.data.remote.NhtsaApi
import io.github.slavikjunior.kache.sample.data.remote.dto.CanadianSpecsDto
import io.github.slavikjunior.kache.sample.data.remote.dto.MakeDto
import io.github.slavikjunior.kache.sample.data.remote.dto.ModelDto
import io.github.slavikjunior.kache.sample.data.repository.DefaultCarRepository
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.serialization.builtins.ListSerializer

/**
 * Android composition root.
 *
 * Uses `:cache-android`'s `l2Cache(context)` factory rather than assembling the engine and
 * the cache by hand: that factory is the module's reason to exist, and it derives the
 * storage directory from the app's private cache directory, so cached data is removed with
 * the app.
 *
 * @param context Any context; the application context is retained.
 */
fun createAppContainer(context: Context): AppContainer {
    val log = CacheEventLog()

    val client = HttpClient(Android) {
        installCarClientDefaults()
    }

    val makesCache = l2Cache(
        context = context,
        serializer = KotlinxJsonSerializer(ListSerializer(MakeDto.serializer())),
        defaultTtl = CacheTtls.MAKES,
        cacheDirName = CacheNames.MAKES,
        logger = log,
    )

    val modelsCache = l2Cache(
        context = context,
        serializer = KotlinxJsonSerializer(ListSerializer(ModelDto.serializer())),
        defaultTtl = CacheTtls.MODELS,
        cacheDirName = CacheNames.MODELS,
        retryPolicy = MODELS_RETRY,
        maxSize = CacheLimits.MODELS_RECORDS,
        logger = log,
    )

    val specsCache = l2Cache(
        context = context,
        serializer = KotlinxJsonSerializer(ListSerializer(CanadianSpecsDto.serializer())),
        defaultTtl = CacheTtls.SPECS,
        cacheDirName = CacheNames.SPECS,
        maxSize = CacheLimits.SPECS_RECORDS,
        logger = log,
    )

    return AppContainer(
        cacheLog = log,
        carRepository = DefaultCarRepository(
            remote = NhtsaApi(client),
            makesCache = makesCache,
            modelsCache = modelsCache,
            specsCache = specsCache,
        ),
    )
}
