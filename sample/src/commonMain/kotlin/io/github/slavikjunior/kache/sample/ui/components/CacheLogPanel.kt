package io.github.slavikjunior.kache.sample.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.slavikjunior.kache.sample.core.cachelog.CacheCounters
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEvent
import io.github.slavikjunior.kache.sample.core.cachelog.CacheEventKind
import io.github.slavikjunior.kache.sample.ui.theme.KacheSampleTheme
import kotlin.math.roundToInt

/**
 * Collapsible panel showing what the cache reported.
 *
 * The collapsed row carries the totals, because those are the numbers worth glancing at;
 * expanding reveals the sequence, which is what explains a number that looks wrong. Only
 * the expansion is remembered across configuration changes — the events themselves come
 * from the ViewModel, and no event must be replayed from a bundle.
 *
 * @param counters Totals since the last clear.
 * @param events Most recent events, newest first.
 */
@Composable
internal fun CacheLogPanel(
    counters: CacheCounters,
    events: List<CacheEvent>,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val toggleDescription = if (expanded) "Hide cache events" else "Show cache events"

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics { contentDescription = toggleDescription }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = "Cache", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = counters.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.Filled.KeyboardArrowUp
                    },
                    contentDescription = null,
                )
            }

            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (events.isEmpty()) {
                        Text(
                            text = "No cache events yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        events.forEach { event -> CacheLogRow(event) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CacheLogRow(event: CacheEvent) {
    Text(
        text = event.message,
        modifier = Modifier.padding(vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = event.kind.color(),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CacheEventKind.color() = when (this) {
    CacheEventKind.HIT -> MaterialTheme.colorScheme.primary
    CacheEventKind.MISS -> MaterialTheme.colorScheme.onSurfaceVariant
    CacheEventKind.FETCH -> MaterialTheme.colorScheme.tertiary
    CacheEventKind.RETRY -> MaterialTheme.colorScheme.tertiary
    CacheEventKind.ERROR -> MaterialTheme.colorScheme.error
    CacheEventKind.EVICTION -> MaterialTheme.colorScheme.secondary
}

private fun CacheCounters.summary(): String {
    val ratio = (hitRatio * 100).roundToInt()
    return "$hits hit · $misses miss · $retries retry · $evictions evict · $ratio%"
}

@Preview(showBackground = true, name = "Cache log expanded")
@Composable
private fun CacheLogPanelPreview() {
    KacheSampleTheme {
        CacheLogPanel(
            counters = CacheCounters(hits = 2, misses = 1, fetches = 2, retries = 1),
            events = listOf(
                CacheEvent(id = 5, kind = CacheEventKind.EVICTION, message = "evict · cars:models:tesla"),
                CacheEvent(id = 4, kind = CacheEventKind.RETRY, message = "retry · cars:models:tesla · attempt 1 failed, next in 400 ms"),
                CacheEvent(id = 3, kind = CacheEventKind.FETCH, message = "fetch · cars:models:tesla · attempt 1"),
                CacheEvent(id = 2, kind = CacheEventKind.MISS, message = "miss · cars:models:tesla · NetworkFirst"),
                CacheEvent(id = 1, kind = CacheEventKind.HIT, message = "hit · cars:makes · DISK"),
            ),
        )
    }
}
