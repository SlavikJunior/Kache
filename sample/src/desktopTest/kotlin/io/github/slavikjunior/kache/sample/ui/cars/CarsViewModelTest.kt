package io.github.slavikjunior.kache.sample.ui.cars

import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.KmpCache
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEvent
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventKind
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventLog
import io.github.slavikjunior.kache.sample.domain.model.CarMake
import io.github.slavikjunior.kache.sample.domain.model.CarModel
import io.github.slavikjunior.kache.sample.domain.model.CarSpec
import io.github.slavikjunior.kache.sample.domain.model.CarSpecSheet
import io.github.slavikjunior.kache.sample.domain.model.LATEST_SPEC_YEAR
import io.github.slavikjunior.kache.sample.domain.repository.CacheRequest
import io.github.slavikjunior.kache.sample.domain.repository.CarRepository
import io.github.slavikjunior.kache.sample.testutil.testCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the screen graph: what each user action does to [CarsUiState], with reads performed
 * by the library's `KacheStateHolder` over real caches.
 *
 * JVM-only on purpose. The ViewModel drives `viewModelScope`, which runs on
 * `Dispatchers.Main`; overriding Main with a test dispatcher is a JVM facility, so these
 * tests live in `desktopTest` rather than in `commonTest`. The pieces they depend on that are
 * platform-independent — the holder, the log, the mappers — are covered in `commonTest` and
 * in `:cache-core`.
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
        assertNull(makes.error)
    }

    @Test
    fun aRefreshKeepsTheCatalogueOnScreenWhileTheNetworkIsBusy() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel()
        advanceUntilIdle()
        repository.fetchDelayMillis = 1_000

        viewModel.loadMakes(refresh = true)
        testScheduler.runCurrent()

        val makes = viewModel.state.value.makes
        assertEquals(
            listOf("HONDA", "TESLA"),
            makes.items.map { it.name },
            "a refresh dims the screen, it does not blank it",
        )
        assertTrue(makes.isLoading, "the holder reports the read as in flight")
    }

    @Test
    fun aFailureWithoutDataBecomesAMessageOnScreen() = runTest(dispatcher.scheduler) {
        repository.failure = IllegalStateException("offline")

        val viewModel = viewModel()
        advanceUntilIdle()

        val makes = viewModel.state.value.makes
        assertEquals("Network request failed", makes.error)
        assertTrue(makes.items.isEmpty())
        assertTrue(!makes.isLoading, "a failed read must stop the spinner")
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
        assertEquals("HONDA", repository.lastModelsMake)
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
        repository.specs = emptyList()
        viewModel.selectYear(target)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(target, state.selectedYear)
        assertEquals(target, repository.lastSpecsYear)
        assertTrue(state.specs.items.isEmpty(), "last year's measurements must not survive a new year label")
        assertEquals(CacheOrigin.NETWORK, state.specs.origin, "the new year is a different record")
    }

    @Test
    fun reloadingRepeatsTheRequestThatProducedTheScreen() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectMake(CarMake(id = 474, name = "HONDA"))
        advanceUntilIdle()
        assertEquals(1, repository.modelsFetches)

        viewModel.reloadModels()
        advanceUntilIdle()

        assertEquals(2, repository.modelsFetches)
        assertEquals("HONDA", repository.lastModelsMake, "a retry must not drift to another key")
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
    fun clearCacheEmptiesStorageTheLogAndReloads() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel()
        advanceUntilIdle()
        log.onMiss("cars:makes", CacheStrategy.CacheFirst)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.log.isNotEmpty())

        viewModel.clearCache()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.log.isEmpty(), "a cleared cache starts its log over")
        assertEquals(0, viewModel.state.value.counters.hits)
        assertEquals(2, repository.makesFetches, "the catalogue is re-read after a clear")
    }

    @Test
    fun cacheEventsReachTheStateAndOnlyResultsBecomeEffects() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<CacheEvent>()
        val collector = launch { viewModel.effects.collect { effects += it } }
        advanceUntilIdle()

        log.onMiss("cars:makes", CacheStrategy.CacheFirst)
        log.onFetch("cars:makes", attempt = 1)
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.log.size, "the panel shows both")
        assertEquals(listOf(CacheEventKind.MISS), effects.map { it.kind }, "a fetch has no result to announce yet")

        collector.cancel()
    }
}

/** Repository double: real caches, configured fetchers, recorded calls. */
private class FakeCarRepository(
    override val makesCache: KmpCache<String, List<CarMake>> = testCache(ListSerializer(CarMake.serializer())),
    override val modelsCache: KmpCache<String, List<CarModel>> = testCache(ListSerializer(CarModel.serializer())),
    override val specsCache: KmpCache<String, List<CarSpecSheet>> = testCache(ListSerializer(CarSpecSheet.serializer())),
) : CarRepository {

    var makes: List<CarMake> = listOf(CarMake(id = 474, name = "HONDA"), CarMake(id = 441, name = "TESLA"))
    var models: List<CarModel> = listOf(CarModel(id = 1863, name = "Civic"))
    var specs: List<CarSpecSheet> = listOf(
        CarSpecSheet(
            trim = "ACCORD",
            dataYear = "26",
            specs = listOf(CarSpec(label = "Overall length", value = "480", unit = "cm")),
        ),
    )

    /** When set, every fetcher throws it — the offline case. */
    var failure: Throwable? = null

    /** Makes the makes fetcher suspend, so a test can observe the in-flight state. */
    var fetchDelayMillis: Long = 0

    var makesFetches: Int = 0
        private set
    var modelsFetches: Int = 0
        private set
    var lastModelsMake: String? = null
        private set
    var lastSpecsYear: Int? = null
        private set
    var lastSpecsModel: String? = null
        private set

    override fun makesRequest(refresh: Boolean): CacheRequest<String, List<CarMake>> = CacheRequest(
        key = "cars:makes",
        strategy = if (refresh) CacheStrategy.CacheAndNetwork else CacheStrategy.CacheFirst,
        fetch = {
            makesFetches++
            if (fetchDelayMillis > 0) delay(fetchDelayMillis)
            failure?.let { throw it }
            makes.sortedBy(CarMake::name)
        },
    )

    override fun modelsRequest(makeName: String): CacheRequest<String, List<CarModel>> = CacheRequest(
        key = "cars:models:${makeName.lowercase()}",
        strategy = CacheStrategy.NetworkFirst,
        fetch = {
            modelsFetches++
            lastModelsMake = makeName
            failure?.let { throw it }
            models
        },
    )

    override fun specsRequest(
        makeName: String,
        modelName: String,
        year: Int,
    ): CacheRequest<String, List<CarSpecSheet>> = CacheRequest(
        key = "cars:specs:$year:${makeName.lowercase()}:${modelName.lowercase()}",
        strategy = CacheStrategy.StaleWhileRevalidate,
        fetch = {
            lastSpecsYear = year
            lastSpecsModel = modelName
            failure?.let { throw it }
            specs
        },
    )
}
