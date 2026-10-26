package io.github.slavikjunior.kache.core

import kotlin.time.Duration

/**
 * Observer for what a cache actually does.
 *
 * A cache is invisible by design: a screen that shows nothing could be a miss, an expired
 * record, a failed decode or a backend that never reaped, and without an observer the only
 * way to tell them apart is a debugger. Implementations of this interface make those
 * events visible, and tests can assert on them instead of inferring behaviour from return
 * values alone.
 *
 * Every method has a no-op default, so an implementation only overrides what it needs:
 *
 * ```kotlin
 * class HitRatio : KacheLogger {
 *     var hits = 0; private set
 *     var misses = 0; private set
 *
 *     override fun onHit(key: String, origin: CacheOrigin) { hits++ }
 *     override fun onMiss(key: String, strategy: CacheStrategy) { misses++ }
 * }
 * ```
 *
 * **Values are never passed to these methods, and must never be logged by an
 * implementation.** Keys are passed already converted through the cache's `keyToString`,
 * so a key that is sensitive has to be redacted at the point where the cache is
 * configured, not here.
 *
 * Calls arrive from whichever thread drives the read or the write, and may interleave, so
 * implementations must be thread-safe. They must also be cheap: they run inline in the
 * read path.
 */
public interface KacheLogger {

    /**
     * The tier held nothing usable for [key] when [strategy] asked for it.
     *
     * An expired record counts as a miss, because no caller may serve it as fresh.
     *
     * @param key Key that missed, already converted to its storage string.
     * @param strategy Strategy the read was running under.
     */
    public fun onMiss(key: String, strategy: CacheStrategy): Unit = Unit

    /**
     * The tier answered from cache.
     *
     * @param key Key that hit.
     * @param origin Where the value came from, including whether it had already expired.
     */
    public fun onHit(key: String, origin: CacheOrigin): Unit = Unit

    /**
     * A fetcher is about to run.
     *
     * @param key Key being fetched.
     * @param attempt 1-based number of this attempt.
     */
    public fun onFetch(key: String, attempt: Int): Unit = Unit

    /**
     * An attempt failed and [retryPolicy][RetryPolicy] scheduled another one.
     *
     * @param key Key being fetched.
     * @param failedAttempt 1-based number of the attempt that failed; the next attempt is
     *   `failedAttempt + 1`.
     * @param delay How long the pipeline waits before that next attempt.
     */
    public fun onRetry(key: String, failedAttempt: Int, delay: Duration): Unit = Unit

    /**
     * A read ended as [CacheResult.Error].
     *
     * This is the only event that reports a [KacheException], so an implementation that
     * wants failures to be visible has to override it.
     *
     * @param key Key the read was for.
     * @param error Failure that was reported to the caller.
     */
    public fun onError(key: String, error: KacheException): Unit = Unit

    /**
     * A record was dropped to stay within the configured `maxSize`.
     *
     * Expired records removed by a reap are not evictions: nothing was given up to make
     * room, the record was already dead.
     *
     * @param key Storage key of the dropped record.
     */
    public fun onEvict(key: String): Unit = Unit

    /** No-op logger, the default for every cache. */
    public companion object {
        public val None: KacheLogger = object : KacheLogger {}
    }
}
