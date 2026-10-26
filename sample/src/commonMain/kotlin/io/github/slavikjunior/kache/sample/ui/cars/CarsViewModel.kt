package io.github.slavikjunior.kache.sample.ui.cars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEvent
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventLog
import io.github.slavikjunior.kache.sample.di.AppContainer
import io.github.slavikjunior.kache.sample.domain.model.CarMake
import io.github.slavikjunior.kache.sample.domain.model.CarModel
import io.github.slavikjunior.kache.sample.domain.repository.CarRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder for the cars screen graph.
 *
 * One [CarsUiState] is exposed at a time, and every change goes through
 * [MutableStateFlow.update] against the current value, so a late emission from a cancelled
 * read cannot resurrect a state that was already replaced.
 *
 * One-shot notifications are separate from the state: a snackbar must be shown once, and a
 * snackbar that lives in the state would reappear after every recomposition or rotation.
 * [effects] carries those, and the log they come from stays in the state for the panel.
 *
 * The ViewModel owns no cache of its own: all three caches live in [AppContainer], because
 * a ViewModel dies on a configuration change and a cache must not.
 *
 * Cache strategies are not chosen here — [CarRepository] decides them. The screen only says
 * whether the user asked for fresh data.
 */
class CarsViewModel(
    private val repository: CarRepository,
    private val cacheLog: CacheEventLog,
) : ViewModel() {

    private val _state = MutableStateFlow(CarsUiState())

    /** Everything the screen renders. */
    val state: StateFlow<CarsUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CacheEvent>(extraBufferCapacity = EFFECTS_BUFFER)

    /** Cache events worth a snackbar, emitted once each. */
    val effects: SharedFlow<CacheEvent> = _effects.asSharedFlow()

    private var makesJob: Job? = null
    private var modelsJob: Job? = null
    private var specsJob: Job? = null

    /**
     * Highest event id already turned into a snackbar.
     *
     * The log is a list, so every new event re-emits the whole list. Comparing ids is what
     * turns "the list changed" into "these events are new", and it is why ids are assigned
     * inside the log rather than here.
     */
    private var lastEffectId = 0L

    init {
        viewModelScope.launch {
            cacheLog.state.collect { log ->
                _state.update { it.copy(log = log.events, counters = log.counters) }
                log.events.asReversed().forEach { event ->
                    if (event.id > lastEffectId) {
                        lastEffectId = event.id
                        if (event.kind.worthSnackbar) _effects.tryEmit(event)
                    }
                }
            }
        }
        loadMakes()
    }

    /**
     * Loads the make catalogue.
     *
     * @param refresh True when the user asked for fresh data: the cached list stays on
     *   screen while the request runs, because the repository then reads through
     *   `CacheAndNetwork`.
     */
    fun loadMakes(refresh: Boolean = false) {
        makesJob?.cancel()
        _state.update { it.copy(makes = it.makes.copy(isLoading = true, error = null)) }

        makesJob = viewModelScope.launch {
            repository.makes(refresh).collect { result ->
                _state.update { state -> state.copy(makes = state.makes.folded(result)) }
            }
        }
    }

    /** Opens [make] and loads its models. */
    fun selectMake(make: CarMake) {
        modelsJob?.cancel()
        specsJob?.cancel()
        _state.update {
            it.copy(
                selectedMake = make,
                selectedModel = null,
                models = ContentState(isLoading = true),
                specs = ContentState(),
            )
        }
        loadModels(make)
    }

    /** Returns to the catalogue and drops everything below it from the screen state. */
    fun closeMake() {
        modelsJob?.cancel()
        specsJob?.cancel()
        modelsJob = null
        specsJob = null
        _state.update {
            it.copy(selectedMake = null, selectedModel = null, models = ContentState(), specs = ContentState())
        }
    }

    /** Reloads the models of the open make; no-op when no make is open. */
    fun reloadModels() {
        val make = _state.value.selectedMake ?: return
        modelsJob?.cancel()
        _state.update { it.copy(models = it.models.copy(isLoading = true, error = null)) }
        loadModels(make)
    }

    /** Opens [model] and loads its specifications for the currently selected year. */
    fun selectModel(model: CarModel) {
        specsJob?.cancel()
        _state.update {
            it.copy(selectedModel = model, specs = ContentState(isLoading = true))
        }
        loadSpecs()
    }

    /** Returns to the model list. */
    fun closeModel() {
        specsJob?.cancel()
        specsJob = null
        _state.update { it.copy(selectedModel = null, specs = ContentState()) }
    }

    /**
     * Switches the requested model year.
     *
     * A year change is a different record, so the specs list is cleared before the read:
     * leaving the previous year's measurements on screen under a new year label would be
     * the one kind of stale data a user would not forgive.
     */
    fun selectYear(year: Int) {
        if (year == _state.value.selectedYear) return
        specsJob?.cancel()
        _state.update { it.copy(selectedYear = year, specs = ContentState(isLoading = true)) }
        if (_state.value.selectedModel != null) loadSpecs()
    }

    /** Reloads the specifications of the open model; no-op when no model is open. */
    fun reloadSpecs() {
        if (_state.value.selectedModel == null) return
        specsJob?.cancel()
        _state.update { it.copy(specs = it.specs.copy(isLoading = true, error = null)) }
        loadSpecs()
    }

    /**
     * Empties every cache, which is the only way to see a cold read again without
     * reinstalling the app.
     */
    fun clearCache() {
        viewModelScope.launch {
            repository.clearCache()
            cacheLog.clear()
            lastEffectId = 0

            modelsJob?.cancel()
            specsJob?.cancel()
            modelsJob = null
            specsJob = null
            _state.update {
                it.copy(selectedMake = null, selectedModel = null, models = ContentState(), specs = ContentState())
            }
            loadMakes()
        }
    }

    private fun loadModels(make: CarMake) {
        modelsJob = viewModelScope.launch {
            repository.models(make.name).collect { result ->
                _state.update { state -> state.copy(models = state.models.folded(result)) }
            }
        }
    }

    private fun loadSpecs() {
        val make = _state.value.selectedMake ?: return
        val model = _state.value.selectedModel ?: return
        val year = _state.value.selectedYear

        specsJob = viewModelScope.launch {
            repository.specs(makeName = make.name, modelName = model.name, year = year).collect { result ->
                _state.update { state -> state.copy(specs = state.specs.folded(result)) }
            }
        }
    }

    /**
     * Folds one library result into the list state.
     *
     * A failure that carries a fallback keeps the fallback on screen: replacing usable rows
     * with an error would be a worse outcome than showing them with a warning.
     */
    private fun <T> ContentState<T>.folded(result: CacheResult<List<T>>): ContentState<T> = when (result) {
        is CacheResult.Loading -> copy(
            items = result.cachedData ?: items,
            isLoading = true,
            error = null,
        )

        is CacheResult.Success -> ContentState(
            items = result.data,
            origin = result.origin,
            isLoading = false,
            error = null,
        )

        is CacheResult.Error -> copy(
            items = result.cachedData ?: items,
            isLoading = false,
            error = result.error.toUserMessage(),
        )
    }

    companion object {
        private const val EFFECTS_BUFFER = 16

        /** Builds the factory for `viewModel(factory = …)` without a DI framework. */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { CarsViewModel(container.carRepository, container.cacheLog) }
        }
    }
}
