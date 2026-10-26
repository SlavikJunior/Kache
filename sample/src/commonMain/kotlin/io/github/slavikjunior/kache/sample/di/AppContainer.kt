package io.github.slavikjunior.kache.sample.di

import io.github.slavikjunior.kache.core.KacheLogger
import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.core.RetryPolicy
import io.github.slavikjunior.kache.core.StorageEngine
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventLog
import io.github.slavikjunior.kache.sample.domain.repository.CarRepository
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds

/**
 * Composition root, shared by every platform.
 *
 * Manual dependency injection on purpose: the sample has one screen graph, and a DI
 * framework would hide the wiring that the sample exists to show. Everything long-lived is
 * created once per process by the platform entry point (see `createAppContainer` in
 * `androidMain`, `desktopMain` and `iosMain`), so a configuration change — which destroys
 * the activity and the ViewModel — does not drop the in-memory tier or reopen the storage
 * engines.
 */
class AppContainer(
    /** Sink for cache events, rendered by the panel and the snackbars. */
    val cacheLog: CacheEventLog,
    /** The repository the UI talks to. */
    val carRepository: CarRepository,
)

/**
 * Applies the HTTP settings every platform shares.
 *
 * `ignoreUnknownKeys` is set here but not in the cache serializer: a network response may
 * grow fields at any time and should not break the screen, while a record read back from
 * disk was written by this app and is parsed strictly.
 *
 * Two JSON content types are registered because the service is inconsistent: most endpoints
 * answer `application/json`, while `GetCanadianVehicleSpecifications` answers `text/json`.
 * Ktor matches converters by content type, so without the second registration that
 * endpoint fails as `NoTransformationFoundException` — which then looks like a network
 * error, because the cache wraps any fetcher failure into `NetworkException`.
 */
internal fun HttpClientConfig<*>.installCarClientDefaults() {
    expectSuccess = true
    install(ContentNegotiation) {
        val format = Json { ignoreUnknownKeys = true }
        json(format, ContentType.Application.Json)
        json(format, ContentType("text", "json"))
    }
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
    }
}

/**
 * Wraps a platform storage engine in an L2 cache with the sample's serialization settings.
 *
 * Android does not use this: it goes through `:cache-android`'s `l2Cache` factory, which is
 * the API that module exists for. Desktop and iOS have no such convenience layer, so they
 * use this instead — same configuration, no duplicated policy.
 */
internal fun <V : Any> carCache(
    engine: StorageEngine,
    serializer: KSerializer<V>,
    ttl: Duration,
    log: KacheLogger,
    retryPolicy: RetryPolicy = RetryPolicy.None,
    maxSize: Long? = null,
): L2KmpCache<String, V> = L2KmpCache(
    storageEngine = engine,
    valueSerializer = KotlinxJsonSerializer(serializer),
    // The caches are keyed by String; `keyToString` is spelled out because a bare
    // `toString()` default would hide that the record name is the key itself.
    keyToString = { it },
    defaultTtl = ttl,
    retryPolicy = retryPolicy,
    maxSize = maxSize,
    logger = log,
)

/** Directory and database names, kept in one place so the platforms cannot drift. */
internal object CacheNames {
    const val MAKES = "cars-makes"
    const val MODELS = "cars-models"
    const val SPECS = "cars-specs"
}

/** Entry lifetimes. */
internal object CacheTtls {
    /** Makes change a few times a year; a relaunch must read from disk, not from network. */
    val MAKES: Duration = 12.hours

    /** Models are the list a user compares, so it is refreshed on visit but usable offline. */
    val MODELS: Duration = 10.minutes

    /** Measurements of a built car do not change; revalidation is a formality. */
    val SPECS: Duration = 24.hours
}

/** Bounds. Kept small enough that a demo can reach them and see an `evict` event. */
internal object CacheLimits {
    const val MODELS_RECORDS = 12L
    const val SPECS_RECORDS = 24L
}

/** One retry is enough to demonstrate the `retry` event without hiding a real outage. */
internal val MODELS_RETRY: RetryPolicy = RetryPolicy.fixed(attempts = 1, delay = 400.milliseconds)

private const val REQUEST_TIMEOUT_MILLIS = 15_000L
private const val CONNECT_TIMEOUT_MILLIS = 10_000L
