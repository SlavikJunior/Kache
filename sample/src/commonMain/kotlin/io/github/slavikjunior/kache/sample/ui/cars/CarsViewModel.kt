package io.github.slavikjunior.kache.sample.ui.cars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventLog
import io.github.slavikjunior.kache.sample.di.AppContainer
import io.github.slavikjunior.kache.sample.domain.model.CarMake
import io.github.slavikjunior.kache.sample.domain.repository.CarRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder for the cars screen.
 *
 * One [CarsUiState] is exposed at a time, and every change goes through
 * [MutableStateFlow.update] against the current value, so a late emission from a cancelled
 * read cannot resurrect a state that was already replaced.
 *
 * The ViewModel owns no cache of its own: both caches live in [AppContainer], because a
 * ViewModel dies on a configuration change and a cache must not.
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

    private var makesJob: Job? = null
    private var modelsJob: Job? = null

    init {
        viewModelScope.launch {
            cacheLog.events.collect { events -> _state.update { it.copy(log = events) } }
        }
        viewModelScope.launch {
            cacheLog.counters.collect { counters -> _state.update { it.copy(counters = counters) } }
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
        _state.update {
            it.copy(selectedMake = make, models = ContentState(isLoading = true))
        }
        loadModels(make)
    }

    /** Returns to the catalogue and drops the model list from the screen state. */
    fun closeMake() {
        modelsJob?.cancel()
        modelsJob = null
        _state.update { it.copy(selectedMake = null, models = ContentState()) }
    }

    /** Reloads the models of the open make; no-op when no make is open. */
    fun reloadModels() {
        val make = _state.value.selectedMake ?: return
        modelsJob?.cancel()
        _state.update { it.copy(models = it.models.copy(isLoading = true, error = null)) }
        loadModels(make)
    }

    /**
     * Empties both caches, which is the only way to see a cold read again without
     * reinstalling the app.
     */
    fun clearCache() {
        viewModelScope.launch {
            repository.clearCache()
            cacheLog.clear()

            modelsJob?.cancel()
            modelsJob = null
            _state.update { it.copy(selectedMake = null, models = ContentState()) }
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
        /** Builds the factory for `viewModel(factory = …)` without a DI framework. */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { CarsViewModel(container.carRepository, container.cacheLog) }
        }
    }
}
