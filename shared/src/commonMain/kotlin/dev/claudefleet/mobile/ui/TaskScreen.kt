package dev.claudefleet.mobile.ui

import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.WorkTaskLink
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.components.WorkStatusDot
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.model.PastWorkSummary
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog

/** Everything a task's screen reports. */
data class TaskHandlers(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpenSession: (WorkTaskLink) -> Unit = {},
    val onContinue: () -> Unit = {},
    val onStartHere: () -> Unit = {},
    val onOpenPlace: () -> Unit = {},
    val onClosePlace: () -> Unit = {},
    /** A group label, and the note as the sheet has it (the placement's own unless edited). */
    val onPlace: (String, String) -> Unit = { _, _ -> },
    val onClearPlacement: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** Summarise a past session for the journal; close the summary. */
    val onSummarize: (WorkTaskLink) -> Unit = {},
    val onDismissSummary: () -> Unit = {},
)

/**
 * One task: its tracker data, where its org and group come from, and every
 * session it has — active (primary first), suggested, and past, which is
 * never drawn as active. Tracker text is plain text only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskScreen(state: TaskUiState, status: ConnectionStatus, handlers: TaskHandlers = TaskHandlers(), modifier: Modifier = Modifier) {
    state.summary?.let { PastWorkSummaryDialog(it, handlers.onDismissSummary) }
    val task = state.task
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = task?.label ?: "Task",
            subtitle = state.stale ?: task?.title?.takeIf { it.isNotBlank() && task.key != null },
            titleStyle = MaterialTheme.typography.titleMedium,
            navigation = {
                IconButton(onClick = handlers.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = handlers.onRefresh, enabled = !state.loading) {
                    Icon(FleetIcons.Refresh, contentDescription = "Refresh")
                }
            },
        )
        ConnectionBanner(status)
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        if (state.error != null && state.conflict) ReloadRow(handlers.onRefresh)
        if (state.loading || state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        if (state.gone) {
            Text(
                TASK_NOT_VISIBLE,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        if (task == null) return@Column

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "facts") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        task.statusCategory?.let { WorkStatusDot(it) }
                        Text(
                            listOfNotNull(task.statusName, task.trackerName, task.resolution).joinToString(" · ").ifBlank { kindWords(task) },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 6.dp),
                            textDecoration = if (task.unavailable) TextDecoration.LineThrough else null,
                        )
                    }
                    if (task.unavailable) {
                        Text("The tracker no longer answers for this ticket.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (task.trackerDown) {
                        Text("Its tracker is failing to sync — sessions below are what fleet last knew.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (task.assignees.isNotEmpty()) Text("Assigned: ${task.assignees.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    task.url?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    state.orgLine?.let { Text("Organisation: $it", style = MaterialTheme.typography.bodySmall) }
                    Text("Group: ${state.groupLine}", style = MaterialTheme.typography.bodySmall)
                    if (state.trackerControlled) {
                        Text(
                            "The tracker decides this group. Placing it here changes only fleet's view, never the tracker.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.detail?.placement?.note?.takeIf { it.isNotBlank() }?.let { Text("Note: $it", style = MaterialTheme.typography.bodySmall) }
                    if (task.repos.isNotEmpty()) Text("Repositories: ${task.repos.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    state.detail?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                    state.detail?.lastOutcome?.let { o ->
                        val line = listOfNotNull(o.name, o.host?.let { "on $it" }, o.branch, o.prUrl?.let { "PR $it" }).joinToString(" · ")
                        if (line.isNotBlank()) Text("Last: $line", style = MaterialTheme.typography.bodySmall)
                        o.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.canContinue) Button(onClick = handlers.onContinue, enabled = !state.busy) { Text("Continue") }
                        if (state.canStart) OutlinedButton(onClick = handlers.onStartHere, enabled = !state.busy) { Text("Start here") }
                        if (state.canPlace) OutlinedButton(onClick = handlers.onOpenPlace, enabled = !state.busy) { Text("Place in group…") }
                        if (state.canClearPlacement) TextButton(onClick = handlers.onClearPlacement, enabled = !state.busy) { Text("Clear placement") }
                    }
                }
                HorizontalDivider()
            }
            linkSection("Active", state.active, handlers, state)
            linkSection("Suggested", state.suggested, handlers, state)
            linkSection("Past", state.past, handlers, state)
            if (state.active.isEmpty() && state.suggested.isEmpty() && state.past.isEmpty()) {
                item(key = "no-sessions") {
                    Text("No session has worked on this yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
            }
            if (task.sessionsMore > 0) {
                item(key = "more") {
                    Text("…and ${task.sessionsMore} more on the desktop.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
    if (state.placeOpen) PlaceSheet(state, handlers)
}

private fun androidx.compose.foundation.lazy.LazyListScope.linkSection(
    title: String,
    links: List<WorkTaskLink>,
    handlers: TaskHandlers,
    state: TaskUiState,
) {
    if (links.isEmpty()) return
    item(key = "h-$title") {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    items(links, key = { "l-$title-${it.linkId}" }) { link -> SessionLinkRow(link, handlers, state) }
}

@Composable
private fun SessionLinkRow(link: WorkTaskLink, handlers: TaskHandlers, state: TaskUiState) {
    val live = link.sessionId != null && link.state != LinkState.Ended && link.state != LinkState.Rejected
    ListItem(
        modifier = if (live) Modifier.clickable { handlers.onOpenSession(link) } else Modifier,
        headlineContent = {
            Text(
                (if (link.primary && link.state == LinkState.Active) "★ " else "") + link.name.ifBlank { "Session" } + (link.host.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
                color = if (live) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        supportingContent = {
            Column {
                Text(sessionLinkLine(link), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                // The PR itself, one tap away rather than a URL to read.
                link.prUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    val uri = LocalUriHandler.current
                    TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text("Open ${prLabel(url)}") }
                }
            }
        },
        trailingContent = {
            when {
                live -> TextButton(onClick = { handlers.onOpenSession(link) }) { Text("Open") }
                // A past session's own account of what it did, for the journal.
                link.state == LinkState.Ended && state.canSummarize -> TextButton(
                    onClick = { handlers.onSummarize(link) },
                    enabled = state.summarizing == null,
                ) { Text(if (state.summarizing == link.linkId) "Summarising…" else "Summarize") }
            }
        },
    )
}

/** A pull request's URL as the few characters a row has room for: "PR #9", or "PR" when it has no number. */
internal fun prLabel(url: String): String =
    Regex("""/(?:pull|merge_requests)/(\d+)""").find(url)?.groupValues?.get(1)?.let { "PR #$it" } ?: "PR"

/** A session under a task, in a line: its state, why it is here, and what it is doing. */
internal fun sessionLinkLine(link: WorkTaskLink): String = buildList {
    add(linkStateWords(link))
    link.why?.takeIf { it.isNotBlank() }?.let { add(it) }
    if (link.needsYou) add("needs you")
    link.branch?.takeIf { it.isNotBlank() }?.let { add(it) }
    link.prUrl?.takeIf { it.isNotBlank() }?.let { add(prLabel(it)) }
    if (link.crossOrg) add("another organisation")
    if (link.otherTasks > 0) add("+${link.otherTasks} other task${if (link.otherTasks == 1) "" else "s"}")
    link.endReason?.takeIf { it.isNotBlank() && link.state == LinkState.Ended }?.let { add(it) }
}.joinToString(" · ")

private fun kindWords(task: dev.claudefleet.mobile.model.WorkTask): String = when (task.kind) {
    dev.claudefleet.mobile.model.TaskKind.Local -> "Local work"
    dev.claudefleet.mobile.model.TaskKind.Ref -> "A key with no ticket"
    else -> ""
}

/**
 * *Place in group…*: a list of the groups people and rules made, or a new
 * label — never a tracker's or a repository's group, which is where a task
 * sits by itself. The placement's note comes along unless edited here.
 * Never drag and drop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceSheet(state: TaskUiState, handlers: TaskHandlers) {
    var label by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(state.placementNote) }
    val choices = state.knownGroups.filter { label.isBlank() || it.contains(label.trim(), ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = handlers.onClosePlace) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Place in group", style = MaterialTheme.typography.titleLarge)
            if (state.trackerControlled) {
                Text("Only fleet's view changes; the tracker keeps its own project.", style = MaterialTheme.typography.bodySmall)
            }
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (state.error != null && state.conflict) ReloadRow(handlers.onRefresh)
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Group") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!state.connected) {
                Text("Offline — nothing can be placed until the hub is back; nothing is queued.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { handlers.onPlace(label, note) }, enabled = label.isNotBlank() && !state.busy && state.connected) {
                Text("Place in “${label.trim()}”")
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(choices, key = { it }) { g ->
                        ListItem(
                            modifier = Modifier.clickable(enabled = !state.busy && state.connected) { handlers.onPlace(g, note) },
                            headlineContent = { Text(g) },
                        )
                    }
                }
            }
        }
    }
}

/** A past session's summary, as Claude wrote it — untrusted text, drawn plain. */
@Composable
internal fun PastWorkSummaryDialog(summary: PastWorkSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Summary · ${summary.key}") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(summary.summary, style = MaterialTheme.typography.bodySmall)
                Text(
                    listOfNotNull(summary.hostAlias, summary.model, if (summary.truncated) "cut short" else null, "kept in the work's journal").joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
