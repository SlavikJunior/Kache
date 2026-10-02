package io.github.slavikjunior.kache.sample

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.github.slavikjunior.kache.android.KacheableViewModel
import io.github.slavikjunior.kache.android.l2Cache
import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.core.RetryPolicy
import io.github.slavikjunior.kache.storage.FileStorageEngine
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer
import io.github.slavikjunior.kache.storage.createFromContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Value written to the cache by the sample. */
@Serializable
data class UserProfile(
    val id: String,
    val name: String,
    val email: String,
)

/** How a single check ended. */
sealed interface CheckOutcome {
    data class Passed(val detail: String) : CheckOutcome
    data class Failed(val detail: String) : CheckOutcome
}

/** One row of the report. */
data class CheckReport(
    val title: String,
    val outcome: CheckOutcome,
)

/**
 * The sample's own state, holding what the scenario measured.
 *
 * The cached profile itself is not duplicated here: it arrives through
 * [io.github.slavikjunior.kache.android.KacheableViewModel.kacheState]. Keeping the two
 * apart shows what the library provides and what the app still owns.
 */
data class ScenarioState(
    val isRunning: Boolean = false,
    val reports: List<CheckReport> = emptyList(),
    val recordCount: Long? = null,
)

/**
 * Reads a profile through [KacheableViewModel] and also runs a short storage scenario.
 *
 * Two independent things are demonstrated on purpose:
 *
 * - **The reactive read.** [loadProfile] feeds [KacheableViewModel.kacheState], so the
 *   screen renders data, its origin, loading and failure without any state plumbing of
 *   its own.
 * - **The storage scenario.** Four direct reads inspect the engine underneath, because
 *   some properties — that a miss really left the disk, that an entry survives process
 *   death — cannot be observed through the cached value alone.
 *
 * The scenario deliberately ends by writing one more entry, so relaunching the app shows
 * [CacheOrigin.DISK] on the first read. That is the proof that the entry outlived the
 * process rather than merely sitting in memory.
 *
 * The cache lives in [SampleCache], not in this ViewModel: a ViewModel is destroyed on a
 * configuration change, and durable storage must not be.
 */
class SampleViewModel(application: Application) : KacheableViewModel<String, UserProfile>(
    application = application,
    cache = SampleCache.cacheOf(application),
) {

    private val engine: FileStorageEngine = SampleCache.engineOf(application)

    private val _scenario = MutableStateFlow(ScenarioState())

    /** What the storage scenario measured. */
    val scenario: StateFlow<ScenarioState> = _scenario.asStateFlow()

    init {
        // The reactive read the screen renders.
        loadProfile()
        runScenario()
    }

    /**
     * Reads the profile through the cache, letting the library own the state.
     *
     * `NetworkFirst` is chosen deliberately: it exercises the fallback path, since a
     * failure has to leave the cached value on screen rather than blanking it.
     */
    fun loadProfile() {
        load(key = PROFILE_ID, fetcher = ::fetchProfile, strategy = CacheStrategy.NetworkFirst)
    }

    /** Replays the failed read. Safe to call even when nothing has failed yet. */
    fun retryProfile() {
        retry()
    }

    fun runScenario() {
        if (_scenario.value.isRunning) return

        _scenario.update { it.copy(isRunning = true) }
        viewModelScope.launch {
            val reports = mutableListOf<CheckReport>()

            // A previous run leaves an entry behind on purpose, so the first read may
            // already be a hit. Report what actually happened instead of assuming.
            val coldRead = readDirectly(key = PROFILE_ID)
            reports += coldRead.toReport(
                title = "Cold read with fetcher",
                acceptableOrigins = setOf(CacheOrigin.NETWORK, CacheOrigin.DISK),
            )

            reports += readDirectly(key = PROFILE_ID, fetcher = null).toReport(
                title = "Warm read without fetcher",
                acceptableOrigins = setOf(CacheOrigin.DISK),
            )

            // Drop the entry, then read with no fetcher: a miss is the proof that
            // invalidation removed it from the disk and not just from a tier.
            invalidate(PROFILE_ID)
            reports += readDirectly(key = PROFILE_ID, fetcher = null).toReport(
                title = "Read after invalidate",
                acceptableOrigins = emptySet(),
            )

            // Leave one entry behind so the next process start reads from disk.
            SampleCache.cacheOf(getApplication()).put(PROFILE_ID, sampleProfile)
            val recordCount = engine.size()
            reports += CheckReport(
                title = "Entry left for the next launch",
                outcome = if (recordCount > 0) {
                    CheckOutcome.Passed("$recordCount record(s) on disk")
                } else {
                    CheckOutcome.Failed("storage reports 0 records after put")
                },
            )

            _scenario.update {
                it.copy(isRunning = false, reports = reports, recordCount = recordCount)
            }
        }
    }

    /** Clears the cache and reports the resulting storage size. */
    fun clearStorage() {
        viewModelScope.launch {
            clearCache()
            // The reactive state was reset by clearCache(); put the profile back so the
            // screen has something to render again.
            SampleCache.cacheOf(getApplication()).put(PROFILE_ID, sampleProfile)
            _scenario.update { it.copy(recordCount = engine.size()) }
        }
    }

    /** One-shot read used by the scenario, which needs the event rather than the state. */
    private suspend fun readDirectly(
        key: String,
        fetcher: (suspend () -> UserProfile)? = null,
    ): CacheResult<UserProfile> = SampleCache.cacheOf(getApplication())
        .get(key = key, strategy = CacheStrategy.CacheFirst, fetcher = fetcher?.let { f -> { _: String -> f() } })
        .first()

    private suspend fun fetchProfile(@Suppress("UNUSED_PARAMETER") key: String): UserProfile =
        sampleProfile

    /**
     * Converts a cache result into a report row.
     *
     * [acceptableOrigins] lists the origins that count as correct. The first read accepts
     * both [CacheOrigin.NETWORK] (nothing was cached yet) and [CacheOrigin.DISK] (a
     * previous run left an entry behind), so a surviving entry is reported as
     * information rather than as a failure.
     */
    private fun CacheResult<UserProfile>.toReport(
        title: String,
        acceptableOrigins: Set<CacheOrigin>,
    ): CheckReport = when (this) {
        is CacheResult.Success -> CheckReport(
            title = title,
            outcome = if (origin in acceptableOrigins) {
                CheckOutcome.Passed("origin=$origin, name=${data.name}")
            } else {
                CheckOutcome.Failed("origin=$origin, expected one of $acceptableOrigins")
            },
        )

        is CacheResult.Error -> CheckReport(
            title = title,
            outcome = if (error is KacheException.CacheMissException) {
                CheckOutcome.Passed("miss: ${error.message}")
            } else {
                CheckOutcome.Failed("${error::class.simpleName}: ${error.message}")
            },
        )

        is CacheResult.Loading -> CheckReport(
            title = title,
            outcome = CheckOutcome.Failed("flow did not emit a terminal state"),
        )
    }

    private companion object {
        const val PROFILE_ID = "u-42"

        val sampleProfile = UserProfile(
            id = PROFILE_ID,
            name = "Vyacheslav",
            email = "vyacheslav@example.com",
        )
    }
}

/**
 * Process-scoped cache, deliberately owned outside the ViewModel.
 *
 * A ViewModel is destroyed on every configuration change, so a cache held inside one
 * would lose its in-memory tier on each rotation and would be recreated from disk. Both
 * the engine and the cache are therefore created once per process.
 */
private object SampleCache {

    private var engineRef: FileStorageEngine? = null
    private var cacheRef: L2KmpCache<String, UserProfile>? = null

    fun engineOf(application: Application): FileStorageEngine =
        engineRef ?: createFromContext(application).also { engineRef = it }

    fun cacheOf(application: Application): L2KmpCache<String, UserProfile> =
        cacheRef ?: l2Cache(
            storageEngine = engineOf(application),
            serializer = KotlinxJsonSerializer(UserProfile.serializer()),
            defaultTtlMs = ONE_HOUR_MS,
            // A cache must not amplify traffic during an outage, so retries stay off by
            // default. This sample asks for three attempts with exponential backoff and
            // jitter, which spreads the retry load instead of retrying in lockstep.
            // The companion only offers untuned `exponential()`, `aggressive()` and
            // `fixed()`, so a custom schedule goes through the constructor directly.
            retryPolicy = RetryPolicy.Exponential(
                maxAttempts = 3,
                initialDelayMs = 200L,
                maxDelayMs = 2_000L,
                multiplier = 2.0,
                jitterRatio = 0.2,
            ),
        ).also { cacheRef = it }

    private const val ONE_HOUR_MS = 60L * 60L * 1000L
}