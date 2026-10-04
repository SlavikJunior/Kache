package io.github.slavikjunior.kache.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The housekeeping half of a cache over a [StorageEngine]: access tracking, capacity limits
 * and optional periodic reaping.
 *
 * Lives in one place because [L2KmpCache] and [ChainKmpCache] would otherwise each grow their
 * own copy of the same three policies, and the two would drift.
 *
 * Everything here is opt-in and off by default. A cache that was handed no limit, no touch
 * window and no reap interval does exactly what it did before: store, read, and leave expired
 * records where they are.
 *
 * @param storageEngine Backend to maintain.
 * @param timeSource Clock for every decision, injected so tests do not have to wait.
 * @param maxSize Largest number of records to keep, or null for no limit. Values of zero or
 *   less mean no limit too, so that a computed value that lands on zero degrades to "unbounded"
 *   instead of deleting everything.
 * @param evictionStrategy Which records [enforceCapacity] drops first.
 * @param touchGranularity How stale a record's access timestamp may get before a read updates
 *   it. Without this floor, LRU would turn every read into a storage write. Zero means every
 *   read updates it. Ignored while [maxSize] is unset, since nothing would read the result.
 * @param autoReapEvery How often expired records are deleted, or null to never delete them
 *   on a schedule.
 */
internal class StorageMaintenance(
    private val storageEngine: StorageEngine,
    private val timeSource: TimeSource,
    private val maxSize: Long? = null,
    private val evictionStrategy: EvictionStrategy = EvictionStrategy.LRU,
    private val touchGranularity: Duration = DEFAULT_TOUCH_GRANULARITY,
    private val autoReapEvery: Duration? = null,
) {

    private var reaperJob: Job? = null

    /**
     * Records a read, if the stored timestamp has aged past [touchGranularity].
     *
     * Does nothing unless a capacity limit is configured. Without one there will never be an
     * eviction, so nothing would ever read the access timestamps back, and refreshing them
     * would be a storage write per key per window bought for nothing. This is also what keeps
     * a cache that was never asked to bound its size byte-for-byte as quiet as it was before.
     *
     * Bounding the write rate is the second half of the deal: a key read a thousand times a
     * minute costs one write, not a thousand. The consequence is that LRU ordering is accurate
     * to within the granularity window rather than per read, which is far finer than the
     * differences it is used to judge.
     *
     * A [touchGranularity] of zero means "refresh on every read", which is exact but writes on
     * every hit; it exists for tests and for callers who want the precision more than the I/O.
     *
     * Failures are swallowed on purpose. Tracking access is an optimisation for eviction
     * ordering; a backend that cannot do it must not turn a successful read into a thrown
     * [KacheException].
     */
    suspend fun touchIfNeeded(stringKey: String, record: StorageRecord<*>) {
        val limit = maxSize ?: return
        if (limit <= 0) return

        val window = touchGranularity.inWholeMilliseconds
        val now = timeSource.currentTimeMillis()
        if (window > 0 && now - record.lastAccessedAt < window) return

        try {
            storageEngine.touch(stringKey, now)
        } catch (e: KacheException) {
            // Keep the read working: the timestamp is a hint, not the data.
        } catch (e: Exception) {
            // Same reasoning for a backend that fails in its own way.
        }
    }

    /**
     * Drops records until the backend is back within [maxSize].
     *
     * [protectedKey] is never removed: it is the record a write has just persisted, and
     * deleting it would leave a cache whose most recent write silently vanished. One extra
     * candidate is requested so that skipping it still leaves enough to free.
     *
     * A backend that reports an unknown [StorageEngine.size], or cannot rank its records,
     * leaves the cache over the limit rather than guessing what to delete.
     */
    suspend fun enforceCapacity(protectedKey: String?) {
        val limit = maxSize ?: return
        if (limit <= 0) return

        val current = try {
            storageEngine.size()
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            throw KacheException.DiskReadException(e)
        }
        if (current < 0 || current <= limit) return

        var excess = current - limit
        val candidates = storageEngine.evictionCandidates(
            strategy = evictionStrategy,
            limit = (excess + 1).toInt(),
            now = timeSource.currentTimeMillis(),
        )

        for (key in candidates) {
            if (excess <= 0) break
            if (key == protectedKey) continue
            if (storageEngine.remove(key)) excess--
        }
    }

    /**
     * Deletes expired records from the backend, and optionally from an in-memory tier too.
     *
     * @param memoryTier L1 to sweep as well, or null when there is none.
     * @return How many records the backend dropped.
     */
    suspend fun reapExpired(memoryTier: (suspend () -> Int)? = null): Long {
        memoryTier?.invoke()
        return try {
            storageEngine.removeExpired(timeSource.currentTimeMillis())
        } catch (e: KacheException) {
            throw e
        } catch (e: Exception) {
            throw KacheException.DiskWriteException(e)
        }
    }

    /**
     * Starts the periodic sweep when [autoReapEvery] is set.
     *
     * The first sweep waits a full interval rather than running immediately, so constructing a
     * cache never triggers I/O the caller did not ask for at that moment.
     */
    fun startAutoReap(scope: CoroutineScope, memoryTier: (suspend () -> Int)? = null) {
        val every = autoReapEvery ?: return
        if (every <= Duration.ZERO || reaperJob != null) return

        reaperJob = scope.launch {
            while (isActive) {
                delay(every)
                try {
                    reapExpired(memoryTier)
                } catch (e: Exception) {
                    // A failed sweep must not kill the loop; the next interval retries.
                }
            }
        }
    }

    /**
     * Cancels the periodic sweep.
     *
     * Only needed when the cache outlives the scope it was given, or when a sweep should stop
     * while the cache itself stays usable.
     */
    fun stopAutoReap() {
        reaperJob?.cancel()
        reaperJob = null
    }

    internal companion object {
        /**
         * How stale an access timestamp may get before a read refreshes it.
         *
         * A minute is short enough that LRU still tracks real usage, and long enough that a
         * key read in a loop costs one write a minute rather than one per read.
         */
        val DEFAULT_TOUCH_GRANULARITY: Duration = 1.minutes
    }}
