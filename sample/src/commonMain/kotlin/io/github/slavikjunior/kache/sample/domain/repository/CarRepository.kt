package io.github.slavikjunior.kache.sample.domain.repository

import io.github.slavikjunior.kache.core.KmpCache
import io.github.slavikjunior.kache.sample.domain.model.CarMake
import io.github.slavikjunior.kache.sample.domain.model.CarModel
import io.github.slavikjunior.kache.sample.domain.model.CarSpecSheet

/**
 * The car catalogue, as the rest of the app sees it.
 *
 * The repository owns two things the screen must not: which cache backs which list, and the
 * strategy that cache is read with. It hands both over as a [CacheRequest], and the view
 * layer performs the read through `KacheStateHolder` — the library's cross-platform half of
 * the view layer, which owns the loading flag, the origin, the error and the cancellation of
 * a superseded read.
 *
 * The caches hold domain models, not transport DTOs: the holder publishes what the cache
 * stores, so the stored type and the rendered type have to be the same. Mapping happens in
 * the request's fetcher, before the value is written.
 *
 * Keys are the repository's business as well — they are part of the record identity, and a
 * screen that guessed them could silently miss its own data.
 */
interface CarRepository {

    /** Cache holding the make catalogue. One record for the whole list. */
    val makesCache: KmpCache<String, List<CarMake>>

    /** Cache holding one model list per make. */
    val modelsCache: KmpCache<String, List<CarModel>>

    /** Cache holding one specification sheet list per year, make and model. */
    val specsCache: KmpCache<String, List<CarSpecSheet>>

    /**
     * Reads the make catalogue.
     *
     * @param refresh True when the user asked for fresher data: the cached list stays on
     *   screen and the network result replaces it, instead of the list blanking.
     */
    fun makesRequest(refresh: Boolean = false): CacheRequest<String, List<CarMake>>

    /**
     * Reads the models of [makeName].
     *
     * Fetched first with the cached list as the fallback, which is what keeps the screen
     * usable offline after the make has been opened once.
     */
    fun modelsRequest(makeName: String): CacheRequest<String, List<CarModel>>

    /**
     * Reads the specifications of [modelName] produced by [makeName] in [year].
     *
     * Cached values are served immediately and refreshed behind them: a measurement of a car
     * built years ago does not change, so waiting for the network to confirm a 2015 wheelbase
     * would be slower and no more correct.
     *
     * @param year Model year, as the source compiles its data per year.
     */
    fun specsRequest(makeName: String, modelName: String, year: Int): CacheRequest<String, List<CarSpecSheet>>
}
