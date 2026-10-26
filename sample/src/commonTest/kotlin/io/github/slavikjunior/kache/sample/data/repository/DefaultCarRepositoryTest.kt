package io.github.slavikjunior.kache.sample.data.repository

import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.KmpCache
import io.github.slavikjunior.kache.sample.data.remote.CarRemoteDataSource
import io.github.slavikjunior.kache.sample.data.remote.dto.CanadianSpecsDto
import io.github.slavikjunior.kache.sample.data.remote.dto.MakeDto
import io.github.slavikjunior.kache.sample.data.remote.dto.ModelDto
import io.github.slavikjunior.kache.sample.data.remote.dto.NameValueDto
import io.github.slavikjunior.kache.sample.domain.model.CarMake
import io.github.slavikjunior.kache.sample.domain.model.CarModel
import io.github.slavikjunior.kache.sample.domain.model.CarSpecSheet
import io.github.slavikjunior.kache.sample.testutil.testCache
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers the policy layer: the key, the strategy and the fetch of each read the repository
 * hands to the view layer.
 *
 * The caches are real L2 instances over an in-memory engine with the production serializer,
 * so these tests also pin that the domain models round-trip through JSON. What they do *not*
 * re-test is `KacheStateHolder`'s own projection — that belongs to `:cache-core`, which runs
 * its tests on every target.
 */
class DefaultCarRepositoryTest {

    private val remote = FakeCarRemote()
    private val makesCache = testCache(ListSerializer(CarMake.serializer()))
    private val modelsCache = testCache(ListSerializer(CarModel.serializer()))
    private val specsCache = testCache(ListSerializer(CarSpecSheet.serializer()))

    private val repository = DefaultCarRepository(
        remote = remote,
        makesCache = makesCache,
        modelsCache = modelsCache,
        specsCache = specsCache,
    )

    @Test
    fun theCatalogueIsCacheFirstAndARefreshRereadsThroughTheNetwork() {
        val plain = repository.makesRequest()
        assertEquals(CacheStrategy.CacheFirst, plain.strategy)
        assertEquals("cars:makes", plain.key)

        val refresh = repository.makesRequest(refresh = true)
        assertEquals(CacheStrategy.CacheAndNetwork, refresh.strategy)
        assertEquals(plain.key, refresh.key, "a refresh reads the same record, it does not create one")
    }

    @Test
    fun modelsAreFetchedFirstAndSpecsAreServedWhileTheyRevalidate() {
        assertEquals(CacheStrategy.NetworkFirst, repository.modelsRequest("HONDA").strategy)
        assertEquals(
            CacheStrategy.StaleWhileRevalidate,
            repository.specsRequest("HONDA", "Accord", 2015).strategy,
        )
    }

    @Test
    fun namesAreTrimmedAndSortedByTheFetcher() = runTest {
        remote.makes = listOf(make(id = 2, name = "  TESLA "), make(id = 1, name = "ACURA"))

        val data = fetch(repository.makesRequest(), makesCache)

        assertEquals(listOf("ACURA", "TESLA"), data.map { it.name })
    }

    @Test
    fun modelsAreDeduplicatedAndSortedByTheFetcher() = runTest {
        remote.models = listOf(
            model(id = 2, name = "Civic"),
            model(id = 1, name = "Accord"),
            model(id = 2, name = "Civic"),
        )

        val data = fetch(repository.modelsRequest("HONDA"), modelsCache)

        assertEquals(listOf("Accord", "Civic"), data.map { it.name })
    }

    @Test
    fun theModelsKeyIgnoresTheCaseOfTheMakeName() {
        assertEquals(
            repository.modelsRequest("Honda").key,
            repository.modelsRequest("HONDA").key,
            "two spellings must not occupy two records",
        )
    }

    @Test
    fun theSpecsKeySeparatesYearsAndModels() {
        val base = repository.specsRequest("HONDA", "Accord", 2015).key

        assertNotEquals(base, repository.specsRequest("HONDA", "Accord", 2016).key)
        assertNotEquals(base, repository.specsRequest("HONDA", "Civic", 2015).key)
    }

    @Test
    fun theSpecsFetcherMapsCodesToLabelledDomainSheets() = runTest {
        remote.specs = listOf(
            CanadianSpecsDto(
                specs = listOf(
                    NameValueDto("MODEL", "ACCORD 2DR COUPE"),
                    NameValueDto("MYR", "15"),
                    NameValueDto("OL", "480"),
                    NameValueDto("CW", "1603"),
                ),
            ),
        )

        val sheets = fetch(repository.specsRequest("HONDA", "Accord", 2015), specsCache)

        val sheet = sheets.single()
        assertEquals("ACCORD 2DR COUPE", sheet.trim)
        assertEquals("15", sheet.dataYear)
        assertEquals(listOf("Overall length", "Curb weight"), sheet.specs.map { it.label })
        assertEquals(listOf("cm", "kg"), sheet.specs.map { it.unit })
    }

    @Test
    fun aSheetWithNoKnownDimensionsIsDroppedInsteadOfRenderedEmpty() = runTest {
        remote.specs = listOf(CanadianSpecsDto(specs = listOf(NameValueDto("MODEL", "X"))))

        assertTrue(fetch(repository.specsRequest("HONDA", "Accord", 2015), specsCache).isEmpty())
    }

    @Test
    fun aFetchedListRoundTripsThroughTheCacheAsDomainModels() = runTest {
        val request = repository.makesRequest()

        val cold = makesCache.get(request.key, request.strategy) { request.fetch(it) }
            .toList().single() as CacheResult.Success

        val warm = makesCache.get(request.key, request.strategy) { request.fetch(it) }
            .toList().single() as CacheResult.Success

        assertEquals(CacheOrigin.NETWORK, cold.origin)
        assertEquals(CacheOrigin.DISK, warm.origin, "the record must survive the JSON round trip")
        assertEquals(cold.data, warm.data)
        assertEquals(listOf("HONDA", "TESLA"), warm.data.map { it.name })
        assertEquals(1, remote.makesCalls, "a warm record must not fetch again")
    }

    private suspend fun <V : Any> fetch(
        request: io.github.slavikjunior.kache.sample.domain.repository.CacheRequest<String, List<V>>,
        cache: KmpCache<String, List<V>>,
    ): List<V> {
        val result = cache.get(request.key, request.strategy) { request.fetch(it) }
            .toList().single() as CacheResult.Success
        return result.data
    }

    private fun make(id: Int, name: String) = MakeDto(
        id = id,
        name = name,
        vehicleTypeId = 2,
        vehicleTypeName = "Passenger Car",
    )

    private fun model(id: Int, name: String) = ModelDto(
        makeId = 474,
        makeName = "Honda",
        id = id,
        name = name,
    )
}

/** Transport double: records calls, answers with configured data, can be told to fail. */
private class FakeCarRemote : CarRemoteDataSource {

    var makes: List<MakeDto> = listOf(
        MakeDto(id = 441, name = "TESLA", vehicleTypeId = 2, vehicleTypeName = "Passenger Car"),
        MakeDto(id = 474, name = "HONDA", vehicleTypeId = 2, vehicleTypeName = "Passenger Car"),
    )
    var models: List<ModelDto> = listOf(
        ModelDto(makeId = 474, makeName = "Honda", id = 1863, name = "Civic"),
        ModelDto(makeId = 474, makeName = "Honda", id = 1861, name = "Accord"),
    )
    var specs: List<CanadianSpecsDto> = emptyList()

    /** When set, every call throws it — the offline case. */
    var failure: Throwable? = null

    var makesCalls: Int = 0
        private set
    var modelsCalls: Int = 0
        private set
    var specsCalls: Int = 0
        private set

    override suspend fun carMakes(): List<MakeDto> {
        makesCalls++
        failure?.let { throw it }
        return makes
    }

    override suspend fun modelsForMake(makeName: String): List<ModelDto> {
        modelsCalls++
        failure?.let { throw it }
        return models
    }

    override suspend fun specificationsFor(
        makeName: String,
        modelName: String,
        year: Int,
    ): List<CanadianSpecsDto> {
        specsCalls++
        failure?.let { throw it }
        return specs
    }
}
