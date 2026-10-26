package io.github.slavikjunior.kache.sample.data.repository

import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.KmpCache
import io.github.slavikjunior.kache.sample.data.mapper.toDomain
import io.github.slavikjunior.kache.sample.data.remote.CarRemoteDataSource
import io.github.slavikjunior.kache.sample.data.remote.dto.ModelDto
import io.github.slavikjunior.kache.sample.domain.model.CarMake
import io.github.slavikjunior.kache.sample.domain.model.CarModel
import io.github.slavikjunior.kache.sample.domain.model.CarSpecSheet
import io.github.slavikjunior.kache.sample.domain.repository.CacheRequest
import io.github.slavikjunior.kache.sample.domain.repository.CarRepository

/**
 * Cache in front of the vPIC client.
 *
 * This class is where the sample's caching policy lives, and it is deliberately the only
 * place that knows which strategy each screen uses:
 *
 * - **Makes** are `CacheFirst` with a long TTL. They change a few times a year, so a warm
 *   record read is the normal path and the screen renders before any request is made.
 * - **Models** are `NetworkFirst`, so an offline device still shows what was opened before
 *   while an online one always gets the current list.
 * - **Specs** are `StaleWhileRevalidate`: the cached measurement appears at once and the
 *   refresh happens behind it. This is the one place where showing stale data is plainly
 *   better than waiting — a 2015 wheelbase will not change while the user is looking.
 * - **Refresh** is `CacheAndNetwork`: the cached list stays on screen and the network result
 *   replaces it when it arrives, instead of blanking the list while a request is in flight.
 *
 * Mapping to domain happens inside each fetcher, so the cache holds [CarMake]/[CarModel]/
 * [CarSpecSheet] rather than DTOs. That is what lets the view layer publish directly from the
 * cache: `KacheStateHolder` exposes the stored value, and an intermediate DTO would either
 * leak into the screen or need a reverse mapper that a DTO cannot supply.
 *
 * @param remote Transport client, unaware of caching.
 * @param makesCache Cache holding the make catalogue.
 * @param modelsCache Cache holding one model list per make.
 * @param specsCache Cache holding one specification sheet list per year, make and model.
 */
internal class DefaultCarRepository(
    private val remote: CarRemoteDataSource,
    override val makesCache: KmpCache<String, List<CarMake>>,
    override val modelsCache: KmpCache<String, List<CarModel>>,
    override val specsCache: KmpCache<String, List<CarSpecSheet>>,
) : CarRepository {

    override fun makesRequest(refresh: Boolean): CacheRequest<String, List<CarMake>> = CacheRequest(
        key = MAKES_KEY,
        strategy = if (refresh) CacheStrategy.CacheAndNetwork else CacheStrategy.CacheFirst,
        fetch = {
            remote.carMakes()
                .map { CarMake(id = it.id, name = it.name.trim()) }
                .sortedBy(CarMake::name)
        },
    )

    override fun modelsRequest(makeName: String): CacheRequest<String, List<CarModel>> = CacheRequest(
        key = modelsKey(makeName),
        strategy = CacheStrategy.NetworkFirst,
        fetch = {
            remote.modelsForMake(makeName)
                .distinctBy(ModelDto::id)
                .map { CarModel(id = it.id, name = it.name) }
                .sortedBy(CarModel::name)
        },
    )

    override fun specsRequest(
        makeName: String,
        modelName: String,
        year: Int,
    ): CacheRequest<String, List<CarSpecSheet>> = CacheRequest(
        key = specsKey(makeName, modelName, year),
        strategy = CacheStrategy.StaleWhileRevalidate,
        fetch = { remote.specificationsFor(makeName, modelName, year).mapNotNull { it.toDomain() } },
    )

    /**
     * Keys carry the make because one record holds a whole model list.
     *
     * Lower-cased so that two spellings of the same make cannot occupy two records; the name
     * itself still goes to the API untouched.
     */
    private fun modelsKey(makeName: String): String = "cars:models:${makeName.lowercase()}"

    /** One record per year, make and model: the source compiles its data per model year. */
    private fun specsKey(makeName: String, modelName: String, year: Int): String =
        "cars:specs:$year:${makeName.lowercase()}:${modelName.lowercase()}"

    private companion object {
        const val MAKES_KEY = "cars:makes"
    }
}
