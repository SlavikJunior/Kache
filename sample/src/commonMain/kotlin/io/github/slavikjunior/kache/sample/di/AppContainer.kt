package io.github.slavikjunior.kache.sample.di

import android.content.Context
import io.github.slavikjunior.kache.android.l2Cache
import io.github.slavikjunior.kache.core.KacheLogger
import io.github.slavikjunior.kache.core.RetryPolicy
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventLog
import io.github.slavikjunior.kache.sample.data.remote.NhtsaApi
import io.github.slavikjunior.kache.sample.data.remote.dto.MakeDto
import io.github.slavikjunior.kache.sample.data.remote.dto.ModelDto
import io.github.slavikjunior.kache.sample.data.repository.DefaultCarRepository
import io.github.slavikjunior.kache.sample.domain.repository.CarRepository
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Composition root, owned by the [io.github.slavikjunior.kache.sample.SampleApplication].
 *
 * Manual dependency injection on purpose: the sample has one screen graph, and a DI
 * framework would hide the wiring that the sample exists to show. Everything long-lived is
 * created once per process here, so a configuration change — which destroys the activity
 * and the ViewModel — does not drop the in-memory tier or reopen the storage engines.
 *
 * @param context Any context; the application context is used internally.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Sink for cache events, rendered by the panel at the bottom of the screen. */
    val cacheLog: CacheEventLog = CacheEventLog()

    /**
     * HTTP client shared by every request.
     *
     * `ignoreUnknownKeys` is set here but not in the cache serializer: a network response
     * may grow fields at any time and should not break the screen, while a record read back
     * from disk was written by this app and is parsed strictly.
     */
    private val httpClient: HttpClient = HttpClient(Android) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        }
    }

    private val makesCache = l2Cache(
        context = appContext,
        serializer = KotlinxJsonSerializer(ListSerializer(MakeDto.serializer())),
        defaultTtl = MAKES_TTL,
        cacheDirName = MAKES_CACHE_DIR,
        logger = cacheLog,
    )

    /**
     * Models are the bounded cache: every make that is opened adds a record, so the limit
     * is what makes an `evict` line appear in the panel once enough makes have been visited.
     */
    private val modelsCache = l2Cache(
        context = appContext,
        serializer = KotlinxJsonSerializer(ListSerializer(ModelDto.serializer())),
        defaultTtl = MODELS_TTL,
        cacheDirName = MODELS_CACHE_DIR,
        retryPolicy = RetryPolicy.fixed(attempts = 1, delay = RETRY_DELAY),
        maxSize = MODELS_MAX_RECORDS,
        logger = cacheLog,
    )

    /** The repository the UI talks to. */
    val carRepository: CarRepository = DefaultCarRepository(
        remote = NhtsaApi(httpClient),
        makesCache = makesCache,
        modelsCache = modelsCache,
    )

    /** Exposed for a future screen that wants to attach the same log to another cache. */
    val logger: KacheLogger get() = cacheLog

    private companion object {
        val MAKES_TTL: Duration = 12.hours
        val MODELS_TTL: Duration = 10.minutes
        val RETRY_DELAY: Duration = 400.milliseconds

        /** Enough for a dozen makes, small enough that the limit is reachable in a demo. */
        const val MODELS_MAX_RECORDS = 12L

        const val MAKES_CACHE_DIR = "cars-makes"
        const val MODELS_CACHE_DIR = "cars-models"

        const val REQUEST_TIMEOUT_MILLIS = 15_000L
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
    }
}
