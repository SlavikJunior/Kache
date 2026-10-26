package io.github.slavikjunior.kache.sample.ui.cars

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.sample.core.cachelog.CacheCounters
import io.github.slavikjunior.kache.sample.domain.model.CarMake
import io.github.slavikjunior.kache.sample.domain.model.CarModel
import io.github.slavikjunior.kache.sample.ui.components.CacheLogPanel
import io.github.slavikjunior.kache.sample.ui.theme.KacheSampleTheme

/**
 * Connects [CarsViewModel] to the screen.
 *
 * Kept separate from [CarsScreen] so the screen itself takes plain state and callbacks: it
 * can be previewed, and it cannot accidentally start a read.
 */
@Composable
fun CarsRoute(
    viewModel: CarsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CarsScreen(
        state = state,
        onRefreshMakes = { viewModel.loadMakes(refresh = true) },
        onSelectMake = viewModel::selectMake,
        onCloseMake = viewModel::closeMake,
        onReloadModels = viewModel::reloadModels,
        onClearCache = viewModel::clearCache,
        modifier = modifier,
    )
}

/**
 * Stateless screen: catalogue of makes, models of the selected make, and the cache log.
 *
 * The log panel sits below the list rather than on a second screen, because the interesting
 * moment is the one right after a tap: `miss` followed by `fetch`, or `hit · DISK` on a
 * relaunch. Splitting it away would hide that sequence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarsScreen(
    state: CarsUiState,
    onRefreshMakes: () -> Unit,
    onSelectMake: (CarMake) -> Unit,
    onCloseMake: () -> Unit,
    onReloadModels: () -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = state.selectedMake

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selected?.let { "Models · ${it.name}" } ?: "Car catalogue",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (selected != null) {
                        IconButton(onClick = onCloseMake) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to makes",
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = if (selected == null) onRefreshMakes else onReloadModels) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = if (selected == null) {
                                "Refresh makes from the network"
                            } else {
                                "Reload models from the network"
                            },
                        )
                    }
                    IconButton(onClick = onClearCache) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Clear both caches",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(Modifier.weight(1f)) {
                if (selected == null) {
                    MakesContent(
                        state = state.makes,
                        onSelectMake = onSelectMake,
                        onRetry = onRefreshMakes,
                    )
                } else {
                    ModelsContent(
                        state = state.models,
                        onRetry = onReloadModels,
                    )
                }
            }

            CacheLogPanel(counters = state.counters, events = state.log)
        }
    }
}

@Composable
private fun MakesContent(
    state: ContentState<CarMake>,
    onSelectMake: (CarMake) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ListHeader(
            title = "Makes",
            count = state.items.size,
            origin = state.origin,
            isLoading = state.isLoading,
        )
        ListBody(
            state = state,
            emptyText = "The catalogue returned no makes.",
            key = CarMake::id,
            onRetry = onRetry,
        ) { make ->
            EntityCard(
                title = make.name,
                supporting = "Make #${make.id}",
                onClick = { onSelectMake(make) },
            )
        }
    }
}

@Composable
private fun ModelsContent(
    state: ContentState<CarModel>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ListHeader(
            title = "Models",
            count = state.items.size,
            origin = state.origin,
            isLoading = state.isLoading,
        )
        ListBody(
            state = state,
            emptyText = "This make has no models in the catalogue.",
            key = CarModel::id,
            onRetry = onRetry,
        ) { model ->
            EntityCard(
                title = model.name,
                supporting = "Model #${model.id}",
            )
        }
    }
}

/**
 * Renders loading, failure, empty and populated states in one place.
 *
 * A failure that still has rows to show is rendered as a notice above them, not instead of
 * them: a stale list with a warning is more useful than an empty screen.
 */
@Composable
private fun <T> ListBody(
    state: ContentState<T>,
    emptyText: String,
    key: (T) -> Any,
    onRetry: () -> Unit,
    itemContent: @Composable (T) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (state.error != null && state.items.isNotEmpty()) {
            StaleDataNotice(state.error)
        }

        when {
            state.items.isNotEmpty() -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = state.items, key = key) { item -> itemContent(item) }
            }

            state.error != null -> CenteredMessage(
                message = state.error,
                actionLabel = "Retry",
                onAction = onRetry,
                isError = true,
            )

            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> CenteredMessage(
                message = emptyText,
                actionLabel = "Refresh",
                onAction = onRetry,
            )
        }
    }
}

@Composable
private fun ListHeader(
    title: String,
    count: Int,
    origin: CacheOrigin?,
    isLoading: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "$count",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            OriginChip(origin)
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

/** Where the rows on screen came from, straight out of [CacheOrigin]. */
@Composable
private fun OriginChip(origin: CacheOrigin?) {
    if (origin == null) return

    val label = when (origin) {
        CacheOrigin.MEMORY -> "cache · memory"
        CacheOrigin.MEMORY_STALE -> "cache · memory · stale"
        CacheOrigin.DISK -> "cache · disk"
        CacheOrigin.DISK_STALE -> "cache · disk · stale"
        CacheOrigin.NETWORK -> "network"
    }
    val isNetwork = origin == CacheOrigin.NETWORK

    Surface(
        color = if (isNetwork) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (isNetwork) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun EntityCard(
    title: String,
    supporting: String,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StaleDataNotice(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = "Showing data that may be out of date · $message",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CenteredMessage(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    isError: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

// --- Previews ---

private val previewMakes = listOf(
    CarMake(id = 441, name = "TESLA"),
    CarMake(id = 474, name = "Honda"),
    CarMake(id = 448, name = "TOYOTA"),
)

private val previewModels = listOf(
    CarModel(id = 1861, name = "Accord"),
    CarModel(id = 1863, name = "Civic"),
    CarModel(id = 1884, name = "Pilot"),
)

@Preview(showBackground = true, name = "Catalogue from disk")
@Composable
private fun CarsScreenCachedPreview() {
    KacheSampleTheme {
        CarsScreen(
            state = CarsUiState(
                makes = ContentState(items = previewMakes, origin = CacheOrigin.DISK),
                counters = CacheCounters(hits = 1, misses = 0, fetches = 1),
            ),
            onRefreshMakes = {},
            onSelectMake = {},
            onCloseMake = {},
            onReloadModels = {},
            onClearCache = {},
        )
    }
}

@Preview(showBackground = true, name = "Models with a stale notice")
@Composable
private fun CarsScreenModelsPreview() {
    KacheSampleTheme {
        CarsScreen(
            state = CarsUiState(
                makes = ContentState(items = previewMakes, origin = CacheOrigin.DISK),
                models = ContentState(
                    items = previewModels,
                    origin = CacheOrigin.DISK_STALE,
                    error = "Network request failed",
                ),
                selectedMake = previewMakes[1],
                counters = CacheCounters(hits = 2, misses = 1, fetches = 2, retries = 1),
            ),
            onRefreshMakes = {},
            onSelectMake = {},
            onCloseMake = {},
            onReloadModels = {},
            onClearCache = {},
        )
    }
}

@Preview(showBackground = true, name = "Empty catalogue")
@Composable
private fun CarsScreenEmptyPreview() {
    KacheSampleTheme {
        CarsScreen(
            state = CarsUiState(makes = ContentState(error = "Network request failed")),
            onRefreshMakes = {},
            onSelectMake = {},
            onCloseMake = {},
            onReloadModels = {},
            onClearCache = {},
        )
    }
}
