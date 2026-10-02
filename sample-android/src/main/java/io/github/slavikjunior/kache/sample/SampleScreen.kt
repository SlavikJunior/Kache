package io.github.slavikjunior.kache.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CachedState

/**
 * Report screen.
 *
 * Two independent sections, mirroring the two things the sample demonstrates:
 *
 * - [ProfileCard] renders [CachedState] straight from
 * [SampleViewModel.kacheState]. It has no local state and no `when (isLoading)` plumbing
 * of its own — the library hands it a value, an origin and an error, and the screen only
 * decides how those look.
 * - The storage scenario below reports what it measured directly against the engine.
 *
 * Every scrollable or clickable element gets a stable size and a content description so
 * the layout survives font scaling and stays readable to accessibility services.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleScreen(
    modifier: Modifier = Modifier,
    viewModel: SampleViewModel = viewModel(),
) {
    val kache by viewModel.kacheState.collectAsStateWithLifecycle()
    val scenario by viewModel.scenario.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Kache sample") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "profile") {
                ProfileCard(
                    state = kache,
                    onRefresh = viewModel::loadProfile,
                    onRetry = viewModel::retryProfile,
                )
            }

            item(key = "divider") { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item(key = "storage") {
                StorageCard(
                    recordCount = scenario.recordCount,
                    isRunning = scenario.isRunning,
                    onReRun = viewModel::runScenario,
                    onClear = viewModel::clearStorage,
                )
            }

            if (scenario.reports.isEmpty()) {
                item(key = "running") {
                    Text(
                        text = "Running cache checks…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            items(scenario.reports, key = { it.title }) { report ->
                CheckCard(report)
            }
        }
    }
}

/**
 * Renders the library-provided [CachedState].
 *
 * The point of this card is that it holds no state: the branch on [CachedState.isLoading]
 * is the only decision, and there is no separate "cached value" field to keep in sync,
 * because a refresh leaves `data` populated.
 */
@Composable
private fun ProfileCard(
    state: CachedState<UserProfile>,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
) {
    val failure = state.error
    val container = if (failure != null) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = if (failure != null) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = buildString {
                    append("Cached profile. ")
                    append(state.data?.let { "Loaded ${it.name} from ${state.origin}. " }
                        ?: "No data yet. ")
                    if (state.isLoading) append("Loading. ")
                    if (failure != null) append("Failed: ${failure.message}.")
                }
            },
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("kacheState", style = MaterialTheme.typography.titleMedium)

            val profile = state.data
            if (profile != null) {
                Text(profile.name, style = MaterialTheme.typography.titleLarge)
                Text(profile.email, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("No profile loaded yet", style = MaterialTheme.typography.bodyMedium)
            }

            // The origin is the library's answer to "why is this on screen", which is the
            // piece a caller most often has to reimplement.
            Text(
                text = "origin=${state.origin ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )

            if (failure != null) {
                Text(
                    text = "${failure::class.simpleName}: ${failure.message}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (failure != null) {
                    ActionButton(
                        text = "Retry",
                        icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    ActionButton(
                        text = "Refresh",
                        icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // A spinner alongside the data rather than instead of it, which is only
            // possible because CachedState keeps both.
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Profile loading" },
                )
            }
        }
    }
}

/** Shows how many records the L2 storage holds, and hosts the action buttons. */
@Composable
private fun StorageCard(
    recordCount: Long?,
    isRunning: Boolean,
    onReRun: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("L2 file storage", style = MaterialTheme.typography.titleMedium)
            Text(
                text = recordCount?.let { "Records on disk: $it" } ?: "Record count unknown",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "After the run one entry is left behind. Relaunch the app and the " +
                    "profile above must report origin=${CacheOrigin.DISK}, which is the proof " +
                    "that the entry survived process death.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    text = "Re-run",
                    icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    enabled = !isRunning,
                    onClick = onReRun,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = "Clear",
                    icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    enabled = !isRunning && recordCount != null && recordCount > 0,
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                )
            }

            if (isRunning) {
                CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Checks running" })
            }
        }
    }
}

/** One check result, colour-coded by outcome. */
@Composable
private fun CheckCard(report: CheckReport) {
    val passed = report.outcome is CheckOutcome.Passed
    val container = if (passed) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = if (passed) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${report.title}. ${report.outcome.detail()}"
            },
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (passed) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(report.title, style = MaterialTheme.typography.titleSmall)
                Text(report.outcome.detail(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: @Composable () -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        icon()
        Text(text = text, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Human readable outcome text, shared by the card and its accessibility description. */
private fun CheckOutcome.detail(): String = when (this) {
    is CheckOutcome.Passed -> detail
    is CheckOutcome.Failed -> detail
}

@Preview(showBackground = true)
@Composable
private fun SampleScreenPreview() {
    KacheSampleTheme {
        SampleScreen()
    }
}