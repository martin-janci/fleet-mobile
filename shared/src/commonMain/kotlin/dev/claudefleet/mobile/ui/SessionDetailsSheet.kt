package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.FleetTask
import dev.claudefleet.mobile.model.SessionEvent
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.StatusDot
import dev.claudefleet.mobile.ui.components.formatUsd

/** What the Details sheet reports. */
data class SessionDetailsHandlers(
    val onClose: () -> Unit = {},
    val onReload: () -> Unit = {},
    val onToggle: (EventCategory) -> Unit = {},
    val onCancelTask: (Long) -> Unit = {},
    val onOpenSession: (Long) -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** The session's worktree screen; null where the hub serves none of it. */
    val onOpenRepo: (() -> Unit)? = null,
)

/**
 * A session's Details, the desktop's details pane as a sheet: the row's facts
 * (host, branch, times, model and cost, context, PR and CI, tags, the session
 * it was forked or reviewed from), the sessions sharing its worktree, the
 * fleet tasks it asked for or works on, and its event timeline with the
 * desktop's filters. Sessions named here open with a tap.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionDetailsSheet(state: SessionDetailsUiState, handlers: SessionDetailsHandlers, sessions: List<SessionRow>) {
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        val row = state.session
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row?.displayName ?: "Session",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = handlers.onReload, enabled = !state.loading) { Text("Refresh") }
                }
                handlers.onOpenRepo?.let { open ->
                    TextButton(onClick = open, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Worktree: changes, history, files") }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            }
            if (row != null) {
                item { Facts(row, state.nowSeconds, sessions, handlers.onOpenSession) }
            }
            if (state.relatedAvailable && state.related.isNotEmpty()) {
                item { SectionTitle("Same worktree") }
                items(state.related, key = { "related:${it.id}" }) { other ->
                    SessionLine(other, onClick = { handlers.onOpenSession(other.id) })
                }
            }
            if (state.tasksAvailable && state.tasks.isNotEmpty()) {
                item { SectionTitle("Tasks") }
                items(state.tasks, key = { "task:${it.id}" }) { task ->
                    TaskLine(task, state, sessions, handlers)
                }
            }
            if (state.historyAvailable) {
                item {
                    SectionTitle("Timeline")
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (category in FILTER_CATEGORIES) {
                            FilterChip(
                                selected = category in state.filter,
                                onClick = { handlers.onToggle(category) },
                                label = { Text(category.label) },
                            )
                        }
                    }
                }
                val shown = state.shownEvents
                if (shown.isEmpty() && !state.loading) {
                    item { Quiet(if (state.events.isEmpty()) "No events recorded yet." else "Nothing in these filters.") }
                }
                items(shown, key = { "event:${it.id}" }) { EventLine(it, state.nowSeconds) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Facts(row: SessionRow, now: Long, sessions: List<SessionRow>, onOpenSession: (Long) -> Unit) {
    val uri = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Fact("Host", row.hostAlias)
        row.branch?.let { Fact("Branch", it) }
        relativeTime(row.startedAt ?: row.createdAt, now)?.let { Fact("Started", "$it ago") }
        relativeTime(row.lastActivityAt, now)?.let { Fact("Last activity", "$it ago") }
        val usage = listOfNotNull(row.usageModel, row.usageCostMicros?.let(::formatUsd)).joinToString(" · ")
        if (usage.isNotEmpty()) Fact("Model", usage)
        row.contextPct?.let { Fact("Context", "${it.toInt()} %") }
        row.prUrl?.let { url ->
            Fact("Pull request", url, onClick = { runCatching { uri.openUri(url) } })
        }
        row.ciStatus?.let { Fact("CI", it) }
        if (row.tags.isNotEmpty()) Fact("Tags", row.tags.joinToString(", "))
        row.parentSessionId?.let { parent ->
            val name = sessions.firstOrNull { it.id == parent }?.displayName ?: "#$parent"
            Fact("Started from", name, onClick = { onOpenSession(parent) })
        }
        row.lastPrompt?.takeIf { it.isNotBlank() }?.let { Fact("Last prompt", it, lines = 3) }
    }
}

@Composable
private fun Fact(label: String, value: String, onClick: (() -> Unit)? = null, lines: Int = 1) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Quiet(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun SessionLine(row: SessionRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(row.claudeStatus, row.stuckKind)
        Column(modifier = Modifier.weight(1f)) {
            Text(row.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.hostAlias, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TaskLine(task: FleetTask, state: SessionDetailsUiState, sessions: List<SessionRow>, handlers: SessionDetailsHandlers) {
    val mine = state.session?.id
    // The other side of the task: who asked, or who is working on it.
    val (role, other) = if (task.requesterSessionId == mine) "Asked" to task.workerSessionId else "Working for" to task.requesterSessionId
    val otherName = other?.let { id -> sessions.firstOrNull { it.id == id }?.displayName ?: "#$id" }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${task.state} · $role" + (otherName?.let { " $it" } ?: ""),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).then(
                    if (other != null) Modifier.clickable { handlers.onOpenSession(other) } else Modifier,
                ),
            )
            if (task.open && state.canCancel) {
                TextButton(onClick = { handlers.onCancelTask(task.id) }, enabled = state.cancelling == null) { Text("Cancel") }
            }
        }
        task.prompt?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        (task.error ?: task.result)?.takeIf { it.isNotBlank() }?.let {
            Text(
                shortDetail(it),
                style = MaterialTheme.typography.bodySmall,
                color = if (task.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EventLine(event: SessionEvent, now: Long) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(
            relativeTime(event.at, now) ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                kindLabel(event.kind),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = if (eventCategory(event) == EventCategory.Errors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            shortDetail(event.detail).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
