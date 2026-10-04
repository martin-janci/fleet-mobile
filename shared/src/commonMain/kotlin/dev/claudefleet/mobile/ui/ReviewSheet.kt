package dev.claudefleet.mobile.ui

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ReviewAlternative
import dev.claudefleet.mobile.model.ReviewItem
import dev.claudefleet.mobile.model.ReviewKind
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.theme.FleetIcons

/** Everything the Review sheet reports. */
data class ReviewHandlers(
    val onClose: () -> Unit = {},
    val onReload: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onConfirm: (ReviewItem) -> Unit = {},
    val onReject: (ReviewItem) -> Unit = {},
    val onKeep: (ReviewItem) -> Unit = {},
    val onRemove: (ReviewItem) -> Unit = {},
    val onMakePrimary: (ReviewItem) -> Unit = {},
    val onToggleChange: (ReviewItem) -> Unit = {},
    val onChange: (ReviewItem, ReviewAlternative) -> Unit = { _, _ -> },
    val onConfirmAll: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onDismissUndo: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * The Review sheet: one card per thing to decide, with the hub's reason;
 * *Undo* for the last decision; *Confirm all shown* with the hub's answer
 * card by card; *Load more* for the cards past the first page. Every card
 * stays until it is decided — nothing moves on by itself. Offline the cards
 * stay under "Offline · as of", and a decision is refused, never queued.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewSheet(state: ReviewUiState, handlers: ReviewHandlers) {
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "To review" + if (state.total > 0) " · ${state.total}" else "",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = handlers.onReload, enabled = state.connected && !state.loading) {
                    Icon(FleetIcons.Refresh, contentDescription = "Refresh")
                }
            }
            if (state.stale != null) {
                StaleNotice("${state.stale} — nothing can be decided until the hub is back; nothing is queued.")
            } else if (!state.connected) {
                StaleNotice("Offline — nothing can be decided until the hub is back; nothing is queued.")
            }
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (state.error != null && state.conflict) ReloadRow(handlers.onReload)
            state.undo?.let { undo ->
                Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(undo.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state.canUndo) TextButton(onClick = handlers.onUndo, enabled = !state.busy) { Text("Undo") }
                        TextButton(onClick = handlers.onDismissUndo) { Text("OK") }
                    }
                }
            }
            if (state.loading || state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (state.canBatch && state.batchCount > 1) {
                OutlinedButton(
                    onClick = handlers.onConfirmAll,
                    enabled = !state.busy,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Text("Confirm all shown (${state.batchCount})") }
            }
            if (state.loaded && state.items.isEmpty()) {
                Text("Nothing to review.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
            }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.items, key = { it.reviewId.ifBlank { "link:${it.linkId}" } }) { item ->
                    ReviewCard(item, state, handlers)
                }
                if (state.hasMore) {
                    item(key = "more") {
                        TextButton(
                            onClick = handlers.onLoadMore,
                            enabled = state.connected && !state.loadingMore,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(if (state.loadingMore) "Loading…" else "Load more (${state.items.size} of ${state.total})")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(item: ReviewItem, state: ReviewUiState, handlers: ReviewHandlers) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(reviewKindWords(item.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        Text(
            item.task.label + (item.task.title.takeIf { it.isNotBlank() && item.task.key != null }?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(item.sessionName.ifBlank { "session ${item.sessionId}" }, item.host).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        for (line in item.why) Text("• $line", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        state.failures[item.linkId]?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val enabled = !state.busy
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (item.kind) {
                ReviewKind.Suggestion -> {
                    if (state.canConfirm) Button(onClick = { handlers.onConfirm(item) }, enabled = enabled) { Text("Confirm") }
                    if (state.canReject) OutlinedButton(onClick = { handlers.onReject(item) }, enabled = enabled) { Text("Reject") }
                    if (state.canChange && item.alternatives.isNotEmpty()) {
                        TextButton(onClick = { handlers.onToggleChange(item) }, enabled = enabled) { Text("Change…") }
                    }
                }
                ReviewKind.CrossOrg, ReviewKind.Unavailable -> {
                    if (state.canKeep) Button(onClick = { handlers.onKeep(item) }, enabled = enabled) { Text("Keep") }
                    if (state.canRemove) OutlinedButton(onClick = { handlers.onRemove(item) }, enabled = enabled) { Text("Remove") }
                }
                ReviewKind.NoPrimary -> {
                    if (state.canMakePrimary) Button(onClick = { handlers.onMakePrimary(item) }, enabled = enabled) { Text("Make primary") }
                }
                ReviewKind.Unknown -> Unit
            }
        }
        if (state.changing == item.linkId) {
            Text("Link it to instead:", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (alt in item.alternatives) {
                    OutlinedButton(onClick = { handlers.onChange(item, alt) }, enabled = enabled) {
                        Text(alt.label + (alt.title.takeIf { it.isNotBlank() && alt.key != null }?.let { " · $it" } ?: ""), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** What a card is about, in words. */
internal fun reviewKindWords(kind: ReviewKind): String = when (kind) {
    ReviewKind.Suggestion -> "Suggested link"
    ReviewKind.CrossOrg -> "Links two organisations"
    ReviewKind.Unavailable -> "Ticket no longer available"
    ReviewKind.NoPrimary -> "No primary task"
    ReviewKind.Unknown -> "Needs a look on the desktop"
}
