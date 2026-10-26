package io.github.slavikjunior.kache.sample.ui.cars

import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.sample.core.cachelog.CacheCounters
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEvent
import io.github.slavikjunior.kache.sample.domain.model.CarMake
import io.github.slavikjunior.kache.sample.domain.model.CarModel

/**
 * One list on screen — makes or models — with what the cache reported about it.
 *
 * The origin is part of the state rather than a throwaway event, because it is the answer
 * to the question the sample asks: this list came from disk, from memory or from the
 * network, and it is visible without opening a debugger.
 *
 * @param items Rows to render.
 * @param origin Where the currently shown rows came from, or null before the first result.
 * @param isLoading True while a result is outstanding and [items] may be stale or empty.
 * @param error Last failure, already turned into a message. Kept next to [items] because a
 *   failure may accompany a usable fallback rather than replace it.
 */
data class ContentState<T>(
    val items: List<T> = emptyList(),
    val origin: CacheOrigin? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    /** True when there is nothing to show and nothing is on its way. */
    val isEmpty: Boolean get() = items.isEmpty() && !isLoading && error == null
}

/**
 * Everything the cars screen renders.
 *
 * The cache log and its counters are part of the state, not of a separate screen: the
 * sample's point is that the cache is observable, and a log nobody watches proves nothing.
 *
 * @param makes Catalogue of makes, shown as the root screen.
 * @param models Models of [selectedMake]; empty while no make is open.
 * @param selectedMake Make whose models are shown, or null for the catalogue.
 * @param log Most recent cache events, newest first.
 * @param counters Totals derived from those events.
 */
data class CarsUiState(
    val makes: ContentState<CarMake> = ContentState(),
    val models: ContentState<CarModel> = ContentState(),
    val selectedMake: CarMake? = null,
    val log: List<CacheEvent> = emptyList(),
    val counters: CacheCounters = CacheCounters(),
)
