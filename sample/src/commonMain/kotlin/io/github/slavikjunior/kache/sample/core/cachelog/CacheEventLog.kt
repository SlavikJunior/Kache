package io.github.slavikjunior.kache.sample.core.cachelog

import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.KacheLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** What kind of cache event a line in the panel represents. */
enum class CacheEventKind { HIT, MISS, FETCH, RETRY, ERROR, EVICTION }

/**
 * One line of the cache log.
 *
 * @param id Monotonic identifier, used as the list key because two identical events are
 *   still two events.
 * @param message Rendered line. Deliberately assembled from keys and origins only: the
 *   logged cache never contains values, so neither does this.
 */
data class CacheEvent(
    val id: Long,
    val kind: CacheEventKind,
    val message: String,
)

/**
 * Totals derived from the event stream.
 *
 * This is what "cache statistics" means in practice: the library reports events, the
 * application counts them. Keeping the counter here rather than in `cache-core` means an
 * app that wants different numbers — per key, per strategy, exported to a backend — is not
 * fighting a shape the library picked for it.
 */
data class CacheCounters(
    val hits: Int = 0,
    val misses: Int = 0,
    val fetches: Int = 0,
    val retries: Int = 0,
    val errors: Int = 0,
    val evictions: Int = 0,
) {
    /** Reads that reached the cache at all. */
    val lookups: Int get() = hits + misses

    /** Share of lookups answered from cache, in `0f..1f`; zero before the first lookup. */
    val hitRatio: Float get() = if (lookups == 0) 0f else hits.toFloat() / lookups
}

/**
 * In-memory [KacheLogger] that feeds the panel on screen.
 *
 * State is exposed as flows rather than as a mutable list because the cache calls these
 * methods from whichever thread is doing the read or the write, and the screen collects
 * them independently.
 */
class CacheEventLog(private val capacity: Int = DEFAULT_CAPACITY) : KacheLogger {

    private val nextId = AtomicLong()

    private val _events = MutableStateFlow<List<CacheEvent>>(emptyList())

    /** Most recent events first, capped at `capacity`. */
    val events: StateFlow<List<CacheEvent>> = _events.asStateFlow()

    private val _counters = MutableStateFlow(CacheCounters())

    /** Running totals since construction or the last [clear]. */
    val counters: StateFlow<CacheCounters> = _counters.asStateFlow()

    override fun onMiss(key: String, strategy: CacheStrategy) {
        record(CacheEventKind.MISS, "miss · $key · ${strategy.name}") {
            it.copy(misses = it.misses + 1)
        }
    }

    override fun onHit(key: String, origin: CacheOrigin) {
        record(CacheEventKind.HIT, "hit · $key · ${origin.name}") {
            it.copy(hits = it.hits + 1)
        }
    }

    override fun onFetch(key: String, attempt: Int) {
        record(CacheEventKind.FETCH, "fetch · $key · attempt $attempt") {
            it.copy(fetches = it.fetches + 1)
        }
    }

    override fun onRetry(key: String, failedAttempt: Int, delay: Duration) {
        record(
            CacheEventKind.RETRY,
            "retry · $key · attempt $failedAttempt failed, next in ${delay.inWholeMilliseconds} ms",
        ) { it.copy(retries = it.retries + 1) }
    }

    override fun onError(key: String, error: KacheException) {
        record(CacheEventKind.ERROR, "error · $key · ${error::class.simpleName}") {
            it.copy(errors = it.errors + 1)
        }
    }

    override fun onEvict(key: String) {
        record(CacheEventKind.EVICTION, "evict · $key") {
            it.copy(evictions = it.evictions + 1)
        }
    }

    /** Forgets the recorded events and the totals, so a demo can start from a clean slate. */
    fun clear() {
        _events.value = emptyList()
        _counters.value = CacheCounters()
    }

    private inline fun record(
        kind: CacheEventKind,
        message: String,
        counter: (CacheCounters) -> CacheCounters,
    ) {
        val event = CacheEvent(id = nextId.incrementAndGet(), kind = kind, message = message)
        _events.update { events -> (listOf(event) + events).take(capacity) }
        _counters.update(counter)
    }

    private companion object {
        const val DEFAULT_CAPACITY = 40
    }
}
