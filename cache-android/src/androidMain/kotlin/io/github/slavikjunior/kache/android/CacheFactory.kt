package io.github.slavikjunior.kache.android

import android.content.Context
import kotlin.time.Duration
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
 * @param V Value type held by the cache.
 * @param storageEngine Persistent backend to store entries in.
 * @param serializer Encodes values on write and decodes them on read.
 * @param defaultTtl How long entries stay fresh when a write carries no TTL of its own.
 * @param retryPolicy How a failing fetcher is retried. [RetryPolicy.None] means the
 *   fetcher runs exactly once, which is the safest default for a cache that must not
 *   amplify traffic during an outage.
 * @param timeSource Clock deciding expiry. Swap in a mutable one to test TTL without
 *   waiting.
 */
public fun <V : Any> l2Cache(
    storageEngine: StorageEngine,
    serializer: KacheSerializer<V>,
    defaultTtl: Duration? = DEFAULT_TTL,
    retryPolicy: RetryPolicy = RetryPolicy.None,
    timeSource: TimeSource = SystemTimeSource,
): L2KmpCache<String, V> = L2KmpCache(
    storageEngine = storageEngine,
    valueSerializer = serializer,
    defaultTtl = defaultTtl,
    retryPolicy = retryPolicy,
    timeSource = timeSource,
)

/**
 * Creates an L2 cache backed by a file engine in the app's private cache directory.
 *
 * The directory is app-private, so entries disappear with the app and are invisible to
 * other apps. Nothing is cleaned up when this function returns: the cache owns no OS
 * resources, and the engine opens each record on demand.
 *
 * @param V Value type held by the cache.
 * @param context Any context; the application context is used internally.
 * @param serializer Encodes values on write and decodes them on read.
 * @param defaultTtl How long entries stay fresh when a write carries no TTL of its own.
 * @param cacheDirName Subdirectory within the app cache directory. Pass a distinct name
 *   to keep several caches apart.
 * @param retryPolicy How a failing fetcher is retried.
 * @param timeSource Clock deciding expiry.
 */
public fun <V : Any> l2Cache(
    context: Context,
    serializer: KacheSerializer<V>,
    defaultTtl: Duration? = DEFAULT_TTL,
    cacheDirName: String = DEFAULT_CACHE_DIR_NAME,
    retryPolicy: RetryPolicy = RetryPolicy.None,
    timeSource: TimeSource = SystemTimeSource,
): L2KmpCache<String, V> = l2Cache(
    storageEngine = createFromContext(context, cacheDirName),
    serializer = serializer,
    defaultTtl = defaultTtl,
    retryPolicy = retryPolicy,
    timeSource = timeSource,
)