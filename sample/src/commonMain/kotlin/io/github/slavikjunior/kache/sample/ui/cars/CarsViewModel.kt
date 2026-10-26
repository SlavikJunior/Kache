package io.github.slavikjunior.kache.sample.ui.cars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.slavikjunior.kache.core.CachedState
import io.github.slavikjunior.kache.core.KacheStateHolder
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEvent
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventLog
import io.github.slavikjunior.kache.sample.di.AppContainer
import io.github.slavikjunior.kache.sample.domain.model.CarMake
import io.github.slavikjunior.kache.sample.domain.model.CarModel
import io.github.slavikjunior.kache.sample.domain.repository.CacheRequest
import io.github.slavikjunior.kache.sample.domain.repository.CarRepository
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
 * The reads themselves are not implemented here. Each list is backed by a
 * [KacheStateHolder] — the library's cross-platform view layer — which owns the parts every
 * caching screen otherwise rewrites: the loading flag, the origin, the error, the fallback
 * that survives a failed refresh, and the cancellation of a superseded read. This class only
 * decides which level is visible and hands the holders the requests the repository defines.
 *
 * It extends a plain `ViewModel` rather than `KacheableViewModel` because that class is an
 * `AndroidViewModel` in an Android-only module, and this code runs on Android, desktop and
 * iOS. The portable half it wraps is `KacheStateHolder`, used directly.
 *
 * One [CarsUiState] is exposed at a time, and every change goes through
 * [MutableStateFlow.update] against the current value, so a late emission from a cancelled
 * read cannot resurrect a state that was already replaced.
 *
 * One-shot notifications are separate from the state: a snackbar must be shown once, and a
 * snackbar that lives in the state would reappear after every recomposition or rotation.
 * [effects] carries those, and the log they come from stays in the state for the panel.
 *
 * The caches live in [AppContainer], not here: a ViewModel dies on a configuration change and
 * a cache must not.
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

    private val makesHolder = KacheStateHolder(viewModelScope, repository.makesCache)
    private val modelsHolder = KacheStateHolder(viewModelScope, repository.modelsCache)
    private val specsHolder = KacheStateHolder(viewModelScope, repository.specsCache)

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

        // Each level publishes into its own slot of the screen state. The holder is the single
        // source of truth for a list, so nothing else writes to these three fields.
        viewModelScope.launch {
            makesHolder.kacheState.collect { cached ->
                _state.update { it.copy(makes = cached.content()) }
            }
        }
        viewModelScope.launch {
            modelsHolder.kacheState.collect { cached ->
                _state.update { it.copy(models = cached.content()) }
            }
        }
        viewModelScope.launch {
            specsHolder.kacheState.collect { cached ->
                _state.update { it.copy(specs = cached.content()) }
            }
        }

        loadMakes()
    }

    /**
     * Loads the make catalogue.
     *
     * @param refresh True when the user asked for fresh data: the cached list stays on
     *   screen while the request runs.
     */
    fun loadMakes(refresh: Boolean = false) {
        makesHolder.read(repository.makesRequest(refresh))
    }

    /** Opens [make] and loads its models. */
    fun selectMake(make: CarMake) {
        _state.update {
            it.copy(selectedMake = make, selectedModel = null, specs = ContentState())
        }
        modelsHolder.read(repository.modelsRequest(make.name))
    }

    /** Returns to the catalogue and drops everything below it from the screen state. */
    fun closeMake() {
        _state.update {
            it.copy(selectedMake = null, selectedModel = null, models = ContentState(), specs = ContentState())
        }
    }

    /** Reloads the models of the open make; no-op when no make is open. */
    fun reloadModels() {
        if (_state.value.selectedMake == null) return
        // The holder remembers the request, so repeating it cannot drift from what was loaded.
        modelsHolder.retry()
    }

    /** Opens [model] and loads its specifications for the currently selected year. */
    fun selectModel(model: CarModel) {
        _state.update { it.copy(selectedModel = model) }
        loadSpecs()
    }

    /** Returns to the model list. */
    fun closeModel() {
        _state.update { it.copy(selectedModel = null, specs = ContentState()) }
    }

    /**
     * Switches the requested model year.
     *
     * A year is a different record, so the holder starts from an empty value: it drops the
     * displayed data whenever the key changes, which is what keeps last year's measurements
     * from sitting under a new year label.
     */
    fun selectYear(year: Int) {
        if (year == _state.value.selectedYear) return
        _state.update { it.copy(selectedYear = year) }
        if (_state.value.selectedModel != null) loadSpecs()
    }

    /** Reloads the specifications of the open model; no-op when no model is open. */
    fun reloadSpecs() {
        if (_state.value.selectedModel == null) return
        specsHolder.retry()
    }

    /**
     * Empties every cache, which is the only way to see a cold read again without
     * reinstalling the app.
     */
    fun clearCache() {
        viewModelScope.launch {
            makesHolder.clearCache()
            modelsHolder.clearCache()
            specsHolder.clearCache()
            cacheLog.clear()
            lastEffectId = 0

            _state.update {
                it.copy(selectedMake = null, selectedModel = null, models = ContentState(), specs = ContentState())
            }
            loadMakes()
        }
    }

    private fun loadSpecs() {
        val make = _state.value.selectedMake ?: return
        val model = _state.value.selectedModel ?: return
        specsHolder.read(repository.specsRequest(make.name, model.name, _state.value.selectedYear))
    }

    /** Performs a request the repository defined, keeping this class free of cache policy. */
    private fun <K, V> KacheStateHolder<K, V>.read(request: CacheRequest<K, V>) {
        load(request.key, request.fetch, request.strategy)
    }

    /**
     * Projects the library's view state onto the list state the screen renders.
     *
     * The library keeps the failure typed, because a caller may need to distinguish a network
     * error from a corrupt record; the screen renders one message, so the translation happens
     * here, once.
     */
    private fun <T> CachedState<List<T>>.content(): ContentState<T> = ContentState(
        items = data.orEmpty(),
        origin = origin,
        isLoading = isLoading,
        error = error?.toUserMessage(),
    )

    companion object {
        private const val EFFECTS_BUFFER = 16

        /** Builds the factory for `viewModel(factory = …)` without a DI framework. */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { CarsViewModel(container.carRepository, container.cacheLog) }
        }
    }
}
