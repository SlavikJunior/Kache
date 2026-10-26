package io.github.slavikjunior.kache.sample.data.repository

import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.core.EvictionStrategy
import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.core.StorageEngine
import io.github.slavikjunior.kache.core.StorageRecord
import io.github.slavikjunior.kache.sample.data.remote.CarRemoteDataSource
import io.github.slavikjunior.kache.sample.data.remote.dto.CanadianSpecsDto
import io.github.slavikjunior.kache.sample.data.remote.dto.MakeDto
import io.github.slavikjunior.kache.sample.data.remote.dto.ModelDto
import io.github.slavikjunior.kache.sample.data.remote.dto.NameValueDto
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Covers the policy layer: which strategy each screen gets, and what the repository does
 * with the result.
 *
 * The caches are real [L2KmpCache] instances over an in-memory engine, and the serializer is
 * the production one, so these tests exercise the same path the app does — including the
 * JSON round trip — without touching the network or the file system.
 */
class DefaultCarRepositoryTest {

    private val remote = FakeCarRemote()

    private val repository = DefaultCarRepository(
        remote = remote,
        makesCache = cache(ListSerializer(MakeDto.serializer())),
        modelsCache = cache(ListSerializer(ModelDto.serializer())),
        specsCache = cache(ListSerializer(CanadianSpecsDto.serializer())),
    )

    @Test
    fun aColdMakeReadFetchesAndReportsNetwork() = runTest {
        val results = repository.makes().toList()

        assertEquals(1, results.size)
        assertEquals(CacheOrigin.NETWORK, (results.single() as CacheResult.Success).origin)
        assertEquals(1, remote.makesCalls)
    }

    @Test
    fun namesAreTrimmedAndSortedBeforeTheyReachTheUi() = runTest {
        remote.makes = listOf(make(id = 2, name = "  TESLA "), make(id = 1, name = "ACURA"))

        val makes = (repository.makes().toList().single() as CacheResult.Success).data

        assertEquals(listOf("ACURA", "TESLA"), makes.map { it.name })
    }

    @Test
    fun aWarmMakeReadIsServedFromStorageWithoutANetworkCall() = runTest {
        repository.makes().toList()

        val second = repository.makes().toList().single() as CacheResult.Success

        assertEquals(CacheOrigin.DISK, second.origin, "CacheFirst must not re-fetch a fresh record")
        assertEquals(1, remote.makesCalls, "the transport must not be touched twice")
    }

    @Test
    fun refreshEmitsTheCachedListAndThenTheNetworkResult() = runTest {
        repository.makes().toList()
        remote.makes = listOf(make(id = 3, name = "BYD"))

        val results = repository.makes(refresh = true).toList()

        assertEquals(2, results.size, "the screen is meant to update twice, not blank")
        assertEquals(CacheOrigin.DISK, (results[0] as CacheResult.Success).origin)
        assertEquals(CacheOrigin.NETWORK, (results[1] as CacheResult.Success).origin)
        assertEquals(listOf("BYD"), (results[1] as CacheResult.Success).data.map { it.name })
    }

    @Test
    fun modelsFallBackToTheCachedListWhenTheNetworkFails() = runTest {
        repository.models("HONDA").toList()
        remote.failure = IllegalStateException("offline")

        val result = repository.models("HONDA").toList().single()

        val success = result as CacheResult.Success
        assertEquals(CacheOrigin.DISK, success.origin, "offline must still show what was opened before")
        assertEquals(listOf("Accord", "Civic"), success.data.map { it.name })
    }

    @Test
    fun oneRecordServesEverySpellingOfAMakeName() = runTest {
        repository.models("Honda").toList()
        remote.failure = IllegalStateException("offline")

        // Different case, same record: the key is normalised, the name sent to the API is not.
        val result = repository.models("HONDA").toList().single() as CacheResult.Success

        assertEquals(2, result.data.size)
    }

    @Test
    fun modelsAreDeduplicatedAndSorted() = runTest {
        remote.models = listOf(
            model(id = 2, name = "Civic"),
            model(id = 1, name = "Accord"),
            model(id = 2, name = "Civic"),
        )

        val models = (repository.models("HONDA").toList().single() as CacheResult.Success).data

        assertEquals(listOf("Accord", "Civic"), models.map { it.name })
    }

    @Test
    fun specsAreServedFromTheStoreAndRefreshedBehindIt() = runTest {
        remote.specs = listOf(specSheet())

        val cold = repository.specs("HONDA", "Accord", 2015).toList().single() as CacheResult.Success
        assertEquals(CacheOrigin.NETWORK, cold.origin)
        assertEquals("Overall length", cold.data.single().specs.single().label)

        val warm = repository.specs("HONDA", "Accord", 2015).toList()

        assertEquals(
            listOf(CacheOrigin.DISK, CacheOrigin.NETWORK),
            warm.map { (it as CacheResult.Success).origin },
            "StaleWhileRevalidate shows the measurement first and revalidates behind it",
        )
    }

    @Test
    fun anOfflineSpecsRefreshKeepsTheCachedSheetRatherThanReportingAFailure() = runTest {
        repository.specs("HONDA", "Accord", 2015).toList()
        remote.failure = IllegalStateException("offline")

        val results = repository.specs("HONDA", "Accord", 2015).toList()

        assertEquals(1, results.size, "a failed refresh must not replace what is on screen")
        assertEquals(CacheOrigin.DISK, (results.single() as CacheResult.Success).origin)
    }

    @Test
    fun aDifferentYearIsADifferentRecord() = runTest {
        repository.specs("HONDA", "Accord", 2015).toList()

        val other = repository.specs("HONDA", "Accord", 2016).toList().single() as CacheResult.Success

        assertEquals(CacheOrigin.NETWORK, other.origin, "2016 must not be answered from the 2015 record")
        assertEquals(2, remote.specsCalls)
    }

    @Test
    fun clearCacheMakesTheNextReadColdAgain() = runTest {
        repository.makes().toList()

        repository.clearCache()

        val afterClear = repository.makes().toList().single() as CacheResult.Success
        assertEquals(CacheOrigin.NETWORK, afterClear.origin)
        assertEquals(2, remote.makesCalls)
    }

    @Test
    fun anEmptySpecificationListIsAValidAnswerNotAFailure() = runTest {
        remote.specs = emptyList()

        val result = repository.specs("HONDA", "Accord", 1998).toList().single()

        val success = result as CacheResult.Success
        assertTrue(success.data.isEmpty(), "the dataset does not cover every model/year")
    }

    private fun <T : Any> cache(serializer: KSerializer<T>): L2KmpCache<String, T> = L2KmpCache(
        storageEngine = InMemoryStorageEngine(),
        valueSerializer = KotlinxJsonSerializer(serializer),
        keyToString = { it },
        defaultTtl = 1.hours,
    )

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

    private fun specSheet() = CanadianSpecsDto(
        specs = listOf(
            NameValueDto("MODEL", "ACCORD"),
            NameValueDto("OL", "480"),
        ),
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

/**
 * Minimal [StorageEngine] for tests: records live in a map.
 *
 * `removeExpired` and `touch` keep the interface defaults — these tests never configure a
 * `maxSize` or an auto-reap, and a fake that silently claimed to support them would make the
 * LRU path look tested when it is not.
 */
private class InMemoryStorageEngine : StorageEngine {

    private val records = mutableMapOf<String, StorageRecord<*>>()

    override suspend fun get(key: String): StorageRecord<*>? = records[key]

    override suspend fun put(key: String, record: StorageRecord<*>) {
        records[key] = record
    }

    override suspend fun remove(key: String): Boolean = records.remove(key) != null

    override suspend fun clear() {
        records.clear()
    }

    override suspend fun size(): Long = records.size.toLong()

    override suspend fun evictionCandidates(
        strategy: EvictionStrategy,
        limit: Int,
        now: Long,
    ): List<String> = records.keys.take(limit)
}
