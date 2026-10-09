package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.relativeAgo
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import dev.claudefleet.mobile.ui.theme.statusLabel
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import dev.claudefleet.mobile.ui.kit.InlineLoading

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
        SessionDetailsList(state, handlers, sessions)
    }
}

/** One of the Details tab's actions: its words, and what a tap does. */
data class DetailsAction(val label: String, val onClick: () -> Unit)

/**
 * The Details sheet's content — on the New bar (redesign 14.4) it is the
 * session's Details tab instead of a sheet, with the session's [actions]
 * (Move to host…, Tasks, the ticket) as a row of buttons under the facts.
 * Everything else in the ⋮ menu stays there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionDetailsList(
    state: SessionDetailsUiState,
    handlers: SessionDetailsHandlers,
    sessions: List<SessionRow>,
    actions: List<DetailsAction> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val row = state.session
    LazyColumn(modifier = modifier.fillMaxWidth()) {
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
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = handlers.onReload, enabled = !state.loading) { Text("Refresh") }
            }
            handlers.onOpenRepo?.let { open ->
                TextButton(onClick = open, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Worktree: changes, history, files") }
            }
            InlineLoading(waiting = state.loading, modifier = Modifier.padding(horizontal = 24.dp))
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        }
        if (row != null) {
            item { Facts(row, state.nowSeconds, sessions, handlers.onOpenSession) }
        }
        if (actions.isNotEmpty()) {
            item {
                SectionTitle("Actions")
                FlowRow(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (action in actions) {
                        OutlinedButton(onClick = action.onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(action.label) }
                    }
                }
            }
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

@Composable
private fun Facts(row: SessionRow, now: Long, sessions: List<SessionRow>, onOpenSession: (Long) -> Unit) {
    val uri = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Fact("Host", row.hostAlias)
        row.branch?.let { Fact("Branch", it) }
        relativeAgo(row.startedAt ?: row.createdAt, now)?.let { Fact("Started", it) }
        relativeAgo(row.lastActivityAt, now)?.let { Fact("Last activity", it) }
        val usage = listOfNotNull(row.usageModel, row.usageCostMicros?.let(::formatUsd)).joinToString(" · ")
        if (usage.isNotEmpty()) Fact("Model", usage)
        row.contextPct?.let { Fact("Context", "${it.toInt()} %") }
        row.prUrl?.let { url ->
            Fact("Pull request", url, onClick = { runCatching { uri.openUri(url) } })
        }
        row.ciStatus?.let { Fact("CI", it) }
        if (row.tags.isNotEmpty()) Fact("Tags", row.tags.joinToString(", "))
        row.parentSessionId?.let { parent ->
            val name = sessions.firstOrNull { it.id == parent }?.displayName ?: "an earlier session"
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
            // A row that does something is a whole 48 dp target, not a line of text.
            .then(if (onClick != null) Modifier.heightIn(min = 48.dp).clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 112.dp),
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
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
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
            // The dot's status in words too: a colour alone says nothing to
            // a screen reader or a colour-blind eye.
            Text(
                "${row.hostAlias} · ${statusLabel(row.claudeStatus, row.stuckKind)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskLine(task: FleetTask, state: SessionDetailsUiState, sessions: List<SessionRow>, handlers: SessionDetailsHandlers) {
    val mine = state.session?.id
    // The other side of the task: who asked, or who is working on it.
    val (role, other) = if (task.requesterSessionId == mine) "Asked" to task.workerSessionId else "Working for" to task.requesterSessionId
    val otherName = other?.let { id -> sessions.firstOrNull { it.id == id }?.displayName ?: "a session no longer listed" }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${task.state.replace('_', ' ')} · $role" + (otherName?.let { " $it" } ?: ""),
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
            modifier = Modifier.widthIn(min = 64.dp),
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
