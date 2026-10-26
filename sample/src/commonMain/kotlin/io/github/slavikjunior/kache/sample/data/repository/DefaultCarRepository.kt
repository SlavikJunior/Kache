package io.github.slavikjunior.kache.sample.data.repository

import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.KmpCache
import io.github.slavikjunior.kache.sample.data.mapper.mapData
import io.github.slavikjunior.kache.sample.data.mapper.toDomain
import io.github.slavikjunior.kache.sample.data.remote.CarRemoteDataSource
import io.github.slavikjunior.kache.sample.data.remote.dto.CanadianSpecsDto
import io.github.slavikjunior.kache.sample.data.remote.dto.MakeDto
import io.github.slavikjunior.kache.sample.data.remote.dto.ModelDto
import io.github.slavikjunior.kache.sample.domain.model.CarMake
import io.github.slavikjunior.kache.sample.domain.model.CarModel
import io.github.slavikjunior.kache.sample.domain.model.CarSpecSheet
import io.github.slavikjunior.kache.sample.domain.repository.CarRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Cache in front of the vPIC client.
 *
 * This class is where the sample's caching policy lives, and it is deliberately the only
 * place that knows which strategy each screen uses:
 *
 * - **Makes** are `CacheFirst` with a long TTL. They change a few times a year, so a warm
 *   disk read is the normal path and the screen renders before any request is made.
 * - **Models** are `NetworkFirst`, so an offline device still shows what was opened before,
 *   while an online one always gets the current list.
 * - **Specs** are `StaleWhileRevalidate`: the cached measurement appears at once and the
 *   refresh happens behind it. This is the one place where showing stale data is plainly
 *   better than waiting — a 2015 wheelbase will not change while the user is looking.
 * - **Refresh** is `CacheAndNetwork`: the cached list stays on screen and the network result
 *   replaces it when it arrives, instead of blanking the list while a request is in flight.
 *
 * DTOs, not domain models, are what the caches store: keeping the payload shape in the data
 * layer means a domain model can change without invalidating every record on disk.
 *
 * @param remote Transport client, unaware of caching.
 * @param makesCache Cache holding `GetMakesForVehicleType/car` payloads.
 * @param modelsCache Cache holding `GetModelsForMake/{make}` payloads.
 * @param specsCache Cache holding `GetCanadianVehicleSpecifications` payloads.
 */
internal class DefaultCarRepository(
    private val remote: CarRemoteDataSource,
    private val makesCache: KmpCache<String, List<MakeDto>>,
    private val modelsCache: KmpCache<String, List<ModelDto>>,
    private val specsCache: KmpCache<String, List<CanadianSpecsDto>>,
) : CarRepository {

    override fun makes(refresh: Boolean): Flow<CacheResult<List<CarMake>>> {
        val strategy = if (refresh) CacheStrategy.CacheAndNetwork else CacheStrategy.CacheFirst
        return makesCache.get(MAKES_KEY, strategy) { remote.carMakes() }
            .map { result ->
                result.mapData { dtos ->
                    dtos.map { CarMake(id = it.id, name = it.name.trim()) }
                        .sortedBy(CarMake::name)
                }
            }
    }

    override fun models(makeName: String): Flow<CacheResult<List<CarModel>>> =
        modelsCache.get(modelsKey(makeName), CacheStrategy.NetworkFirst) { remote.modelsForMake(makeName) }
            .map { result ->
                result.mapData { dtos ->
                    dtos.distinctBy(ModelDto::id)
                        .map { CarModel(id = it.id, name = it.name) }
                        .sortedBy(CarModel::name)
                }
            }

    override fun specs(makeName: String, modelName: String, year: Int): Flow<CacheResult<List<CarSpecSheet>>> =
        specsCache.get(
            key = specsKey(makeName, modelName, year),
            strategy = CacheStrategy.StaleWhileRevalidate,
            fetcher = { remote.specificationsFor(makeName, modelName, year) },
        ).map { result ->
            result.mapData { dtos -> dtos.mapNotNull { it.toDomain() } }
        }

    override suspend fun clearCache() {
        makesCache.clear()
        modelsCache.clear()
        specsCache.clear()
    }

    /**
     * Keys carry the make because one record holds a whole model list.
     *
     * Lower-cased so that two spellings of the same make cannot occupy two records; the
     * name itself still goes to the API untouched.
     */
    private fun modelsKey(makeName: String): String = "cars:models:${makeName.lowercase()}"

    /** One record per year, make and model: the source compiles its data per model year. */
    private fun specsKey(makeName: String, modelName: String, year: Int): String =
        "cars:specs:$year:${makeName.lowercase()}:${modelName.lowercase()}"

    private companion object {
        const val MAKES_KEY = "cars:makes"
    }
}
