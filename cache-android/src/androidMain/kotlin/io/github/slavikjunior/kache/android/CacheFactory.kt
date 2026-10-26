package io.github.slavikjunior.kache.android

import android.content.Context
import kotlin.time.Duration
import io.github.slavikjunior.kache.core.EvictionStrategy
import io.github.slavikjunior.kache.core.KacheLogger
import io.github.slavikjunior.kache.core.KacheSerializer
import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.core.RetryPolicy
import io.github.slavikjunior.kache.core.StorageEngine
import io.github.slavikjunior.kache.core.SystemTimeSource
import io.github.slavikjunior.kache.core.TimeSource
import io.github.slavikjunior.kache.storage.createFromContext
import kotlin.time.Duration.Companion.hours

/** Default entry lifetime applied when a write does not specify its own TTL: one hour. */
public val DEFAULT_TTL: Duration = 1.hours

/** Subdirectory of the app cache directory used by the [Context]-based factory. */
public const val DEFAULT_CACHE_DIR_NAME: String = "kache"

/**
 * Wraps an existing [StorageEngine] in an L2 cache.
 *
 * Prefer this overload when the engine needs to stay reachable — to report
 * [StorageEngine.size], to seed it, or to close it — because ownership stays with the
 * caller. The [Context]-based overload is the shorter form when it does not.
 *
 * **The cache is unbounded unless [maxSize] is set.** That default is deliberate: bounding
 * a cache also turns on access tracking, which costs a storage write per key per touch
 * window, and nothing here should start doing I/O a caller did not ask for. It does mean
 * that a cache left unbounded grows until the device runs out of space, and that expired
 * records are only removed when a key is read or overwritten. Pass [maxSize] for anything
 * long-lived.
 *
 * @param V Value type held by the cache.
 * @param storageEngine Persistent backend to store entries in.
 * @param serializer Encodes values on write and decodes them on read.
 * @param defaultTtl How long entries stay fresh when a write carries no TTL of its own.
 * @param retryPolicy How a failing fetcher is retried. [RetryPolicy.None] means the
 *   fetcher runs exactly once, which is the safest default for a cache that must not
 *   amplify traffic during an outage.
 * @param timeSource Clock deciding expiry. Swap in a mutable one to test TTL without
 *   waiting.
 * @param maxSize Largest number of records to keep, or null for no limit. Set this for any
 *   cache that lives longer than a screen.
 * @param evictionStrategy Which records a write drops first once [maxSize] is exceeded.
 *   Ignored while [maxSize] is null.
 * @param autoReapEvery How often expired records are deleted, or null to never delete them
 *   on a schedule. Enabling it starts a coroutine that lives as long as the cache, so only
 *   set it on a cache that outlives every screen, and call
 *   [L2KmpCache.stopAutoReap] if that stops being true.
 * @param logger Observer for hits, misses, fetch attempts, retries, errors and evictions.
 */
public fun <V : Any> l2Cache(
    storageEngine: StorageEngine,
    serializer: KacheSerializer<V>,
    defaultTtl: Duration? = DEFAULT_TTL,
    retryPolicy: RetryPolicy = RetryPolicy.None,
    timeSource: TimeSource = SystemTimeSource,
    maxSize: Long? = null,
    evictionStrategy: EvictionStrategy = EvictionStrategy.LRU,
    autoReapEvery: Duration? = null,
    logger: KacheLogger = KacheLogger.None,
): L2KmpCache<String, V> = L2KmpCache(
    storageEngine = storageEngine,
    valueSerializer = serializer,
    defaultTtl = defaultTtl,
    retryPolicy = retryPolicy,
    timeSource = timeSource,
    maxSize = maxSize,
    evictionStrategy = evictionStrategy,
    autoReapEvery = autoReapEvery,
    logger = logger,
)

/**
 * Creates an L2 cache backed by a file engine in the app's private cache directory.
 *
 * The directory is app-private, so entries disappear with the app and are invisible to
 * other apps. Nothing is cleaned up when this function returns: the cache owns no OS
 * resources, and the engine opens each record on demand.
 *
 * The cache directory is excluded from Android Auto Backup, which is the right place for
 * cached data. Note that `:cache-store-room`'s `createFromContext` puts its database
 * elsewhere and is not interchangeable with this one on that point.
 *
 * **The cache is unbounded unless [maxSize] is set** — see the engine overload for why.
 *
 * @param V Value type held by the cache.
 * @param context Any context; the application context is used internally.
 * @param serializer Encodes values on write and decodes them on read.
 * @param defaultTtl How long entries stay fresh when a write carries no TTL of its own.
 * @param cacheDirName Subdirectory within the app cache directory. Pass a distinct name
 *   to keep several caches apart.
 * @param retryPolicy How a failing fetcher is retried.
 * @param timeSource Clock deciding expiry.
 * @param maxSize Largest number of records to keep, or null for no limit.
 * @param evictionStrategy Which records a write drops first once [maxSize] is exceeded.
 * @param autoReapEvery How often expired records are deleted, or null to never delete them
 *   on a schedule.
 * @param logger Observer for hits, misses, fetch attempts, retries, errors and evictions.
 */
public fun <V : Any> l2Cache(
    context: Context,
    serializer: KacheSerializer<V>,
    defaultTtl: Duration? = DEFAULT_TTL,
    cacheDirName: String = DEFAULT_CACHE_DIR_NAME,
    retryPolicy: RetryPolicy = RetryPolicy.None,
    timeSource: TimeSource = SystemTimeSource,
    maxSize: Long? = null,
    evictionStrategy: EvictionStrategy = EvictionStrategy.LRU,
    autoReapEvery: Duration? = null,
    logger: KacheLogger = KacheLogger.None,
): L2KmpCache<String, V> = l2Cache(
    storageEngine = createFromContext(context, cacheDirName),
    serializer = serializer,
    defaultTtl = defaultTtl,
    retryPolicy = retryPolicy,
    timeSource = timeSource,
    maxSize = maxSize,
    evictionStrategy = evictionStrategy,
    autoReapEvery = autoReapEvery,
    logger = logger,
)
