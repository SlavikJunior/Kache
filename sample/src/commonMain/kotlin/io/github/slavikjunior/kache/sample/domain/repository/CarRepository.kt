package io.github.slavikjunior.kache.sample.domain.repository

import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.sample.domain.model.CarMake
import io.github.slavikjunior.kache.sample.domain.model.CarModel
import io.github.slavikjunior.kache.sample.domain.model.CarSpecSheet
import kotlinx.coroutines.flow.Flow

/**
 * The car catalogue, as the rest of the app sees it.
 *
 * The cache strategy is a data-layer decision, not a UI one: nothing above this interface
 * chooses between `CacheFirst` and its alternatives. `refresh` is the only lever the UI
 * gets, and it means "get me the freshest data you can" rather than "call the network".
 *
 * The results are [CacheResult]s because the sample exists to show what the cache reports
 * — the origin of each value, and errors that may still carry a fallback. A repository in
 * a real product would usually fold these into its own model before they reach the UI.
 */
interface CarRepository {

    /**
     * Car makes.
     *
     * Served from cache when it is fresh, which is why relaunching the app shows
     * `origin=DISK` without a request.
     *
     * @param refresh When true the cached value is emitted first and the network result
     *   follows, so the screen updates twice instead of blanking.
     */
    fun makes(refresh: Boolean = false): Flow<CacheResult<List<CarMake>>>

    /**
     * Models for [makeName].
     *
     * Fetched first, with the cached list as the fallback — the shape that keeps working
     * when the device is offline and the make was opened before.
     */
    fun models(makeName: String): Flow<CacheResult<List<CarModel>>>

    /**
     * Specifications of [modelName] produced by [makeName] in [year].
     *
     * Cached values are served immediately and refreshed in the background, because a
     * measurement of a car that was built years ago does not change: waiting for the
     * network to confirm a 2015 wheelbase would be slower and no more correct.
     *
     * @param year Model year, as the source compiles its data per year.
     */
    fun specs(makeName: String, modelName: String, year: Int): Flow<CacheResult<List<CarSpecSheet>>>

    /** Drops every entry of every cache, including the records left from previous runs. */
    suspend fun clearCache()
}
