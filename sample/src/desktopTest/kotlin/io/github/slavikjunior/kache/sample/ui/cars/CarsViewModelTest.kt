package io.github.slavikjunior.kache.sample.ui.cars

import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.core.KacheException
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEvent
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventKind
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventLog
import io.github.slavikjunior.kache.sample.domain.model.CarMake
import io.github.slavikjunior.kache.sample.domain.model.CarModel
import io.github.slavikjunior.kache.sample.domain.model.CarSpec
import io.github.slavikjunior.kache.sample.domain.model.CarSpecSheet
import io.github.slavikjunior.kache.sample.domain.model.LATEST_SPEC_YEAR
import io.github.slavikjunior.kache.sample.domain.repository.CarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the state machine: what each user action does to [CarsUiState].
 *
 * JVM-only on purpose. The ViewModel drives `viewModelScope`, which runs on
 * `Dispatchers.Main`; overriding Main with a test dispatcher is a JVM facility, so these
 * tests live in `desktopTest` rather than in `commonTest`. The shared logic they depend on
 * (repository, log, mappers) is covered by `commonTest` on every platform.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeCarRepository()
    private val log = CacheEventLog()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): CarsViewModel = CarsViewModel(repository = repository, cacheLog = log)

    @Test
    fun initialLoadPublishesMakesAndStopsLoading() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel()

        advanceUntilIdle()

        val makes = viewModel.state.value.makes
        assertEquals(listOf("HONDA", "TESLA"), makes.items.map { it.name })
        assertEquals(CacheOrigin.NETWORK, makes.origin)
        assertTrue(!makes.isLoading)
    }

    @Test
    fun aFailureWithoutDataBecomesAMessageOnScreen() = runTest(dispatcher.scheduler) {
        repository.makesResult = CacheResult.Error(
            KacheException.NetworkException(IllegalStateException("offline")),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        val makes = viewModel.state.value.makes
        assertEquals("Network request failed", makes.error)
        assertTrue(makes.items.isEmpty())
    }

    @Test
    fun selectingAMakeLoadsItsModelsAndDropsAnyPreviousSpecs() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val make = viewModel.state.value.makes.items.first()

        viewModel.selectModel(CarModel(id = 1, name = "Civic"))
        advanceUntilIdle()
        assertEquals(CarModel(id = 1, name = "Civic"), viewModel.state.value.selectedModel)

        viewModel.selectMake(make)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(make, state.selectedMake)
        assertNull(state.selectedModel, "opening a make must close the model below it")
        assertEquals(listOf("Civic"), state.models.items.map { it.name })
        assertTrue(state.specs.items.isEmpty())
    }

    @Test
    fun selectingAModelLoadsSpecsForTheSelectedYear() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectMake(viewModel.state.value.makes.items.first())
        advanceUntilIdle()

        viewModel.selectModel(CarModel(id = 1861, name = "Accord"))
        advanceUntilIdle()

        assertEquals(LATEST_SPEC_YEAR, repository.lastSpecsYear)
        assertEquals("Accord", repository.lastSpecsModel)
        assertEquals("Overall length", viewModel.state.value.specs.items.single().specs.single().label)
    }

    @Test
    fun changingTheYearClearsThePreviousTableAndReadsAgain() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectMake(viewModel.state.value.makes.items.first())
        advanceUntilIdle()
        viewModel.selectModel(CarModel(id = 1861, name = "Accord"))
        advanceUntilIdle()

        val target = LATEST_SPEC_YEAR - 5
        repository.specsResult = CacheResult.Success(emptyList(), CacheOrigin.NETWORK)
        viewModel.selectYear(target)
        advanceUntilIdle()

        assertEquals(target, viewModel.state.value.selectedYear)
        assertEquals(target, repository.lastSpecsYear)
        assertTrue(
            viewModel.state.value.specs.items.isEmpty(),
            "last year's measurements must not survive under a new year label",
        )
    }

    @Test
    fun closingAModelAndThenTheMakeDropsBothSelections() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectMake(viewModel.state.value.makes.items.first())
        advanceUntilIdle()
        viewModel.selectModel(CarModel(id = 1861, name = "Accord"))
        advanceUntilIdle()

        viewModel.closeModel()
        val afterModel = viewModel.state.value
        assertNull(afterModel.selectedModel)
        assertTrue(afterModel.specs.items.isEmpty())

        viewModel.closeMake()
        val afterMake = viewModel.state.value
        assertNull(afterMake.selectedMake)
        assertTrue(afterMake.models.items.isEmpty())
    }

    @Test
    fun clearCacheEmptiesTheLogAndReloads() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel()
        advanceUntilIdle()
        log.onMiss("cars:makes", io.github.slavikjunior.kache.core.CacheStrategy.CacheFirst)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.log.isNotEmpty())

        viewModel.clearCache()
        advanceUntilIdle()

        assertEquals(1, repository.clearCalls)
        assertTrue(viewModel.state.value.log.isEmpty(), "a cleared cache starts its log over")
        assertEquals(0, viewModel.state.value.counters.hits)
        assertEquals(2, repository.makesCalls, "the catalogue is reloaded after a clear")
    }

    @Test
    fun cacheEventsReachTheStateAndOnlyResultsBecomeEffects() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<CacheEvent>()
        val collector = launch { viewModel.effects.collect { effects += it } }
        advanceUntilIdle()

        log.onMiss("cars:makes", io.github.slavikjunior.kache.core.CacheStrategy.CacheFirst)
        log.onFetch("cars:makes", attempt = 1)
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.log.size, "the panel shows both")
        assertEquals(listOf(CacheEventKind.MISS), effects.map { it.kind }, "a fetch has no result to announce yet")

        collector.cancel()
    }

    @Test
    fun aStaleSuccessKeepsItsOriginSoTheBadgeTellsTheTruth() = runTest(dispatcher.scheduler) {
        repository.modelsResult = CacheResult.Success(
            data = listOf(CarModel(id = 1, name = "Civic")),
            origin = CacheOrigin.DISK_STALE,
        )

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectMake(viewModel.state.value.makes.items.first())
        advanceUntilIdle()

        assertEquals(CacheOrigin.DISK_STALE, viewModel.state.value.models.origin)
    }
}

/** Repository double: answers from configured results and records what it was asked. */
private class FakeCarRepository : CarRepository {

    var makesResult: CacheResult<List<CarMake>> = CacheResult.Success(
        data = listOf(CarMake(id = 474, name = "HONDA"), CarMake(id = 441, name = "TESLA")),
        origin = CacheOrigin.NETWORK,
    )
    var modelsResult: CacheResult<List<CarModel>> = CacheResult.Success(
        data = listOf(CarModel(id = 1863, name = "Civic")),
        origin = CacheOrigin.NETWORK,
    )
    var specsResult: CacheResult<List<CarSpecSheet>> = CacheResult.Success(
        data = listOf(
            CarSpecSheet(
                trim = "ACCORD",
                dataYear = "26",
                specs = listOf(CarSpec(label = "Overall length", value = "480", unit = "cm")),
            ),
        ),
        origin = CacheOrigin.NETWORK,
    )

    var makesCalls: Int = 0
        private set
    var clearCalls: Int = 0
        private set
    var lastSpecsYear: Int? = null
        private set
    var lastSpecsModel: String? = null
        private set

    override fun makes(refresh: Boolean): Flow<CacheResult<List<CarMake>>> {
        makesCalls++
        return flowOf(makesResult)
    }

    override fun models(makeName: String): Flow<CacheResult<List<CarModel>>> = flowOf(modelsResult)

    override fun specs(makeName: String, modelName: String, year: Int): Flow<CacheResult<List<CarSpecSheet>>> {
        lastSpecsYear = year
        lastSpecsModel = modelName
        return flowOf(specsResult)
    }

    override suspend fun clearCache() {
        clearCalls++
    }
}
