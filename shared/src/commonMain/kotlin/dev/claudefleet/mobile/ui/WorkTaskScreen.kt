package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.components.StatusDot
import dev.claudefleet.mobile.ui.components.WorkStatusDot
import dev.claudefleet.mobile.ui.theme.FleetIcons

data class WorkTaskHandlers(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    val onStartHere: () -> Unit = {},
    val onResumeHost: (String) -> Unit = {},
    val onContinue: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * One task: what it is, every session on it and why each is linked, and
 * Open / Continue / Start here. Every title, description and "why" is text
 * from a tracker or a session, drawn as plain text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkTaskScreen(state: WorkTaskUiState, status: ConnectionStatus, handlers: WorkTaskHandlers, modifier: Modifier = Modifier) {
    val task = state.detail?.task
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = task?.key ?: task?.title?.takeIf { it.isNotBlank() } ?: "Task",
            subtitle = task?.statusName ?: task?.trackerName,
            navigation = {
                IconButton(onClick = handlers.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = handlers.onRefresh) { Icon(FleetIcons.Refresh, contentDescription = "Refresh") }
            },
        )
        ConnectionBanner(status)
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        if (state.notFound) {
            Text(
                "This task is gone, or not one this phone may see.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        if (task == null) {
            Text(if (state.loading) "Loading…" else "", modifier = Modifier.padding(24.dp))
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "head") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        task.statusCategory?.let { WorkStatusDot(it) }
                        Text(
                            task.title.ifBlank { task.key ?: task.taskId },
                            style = MaterialTheme.typography.titleMedium,
                            textDecoration = if (task.unavailable) TextDecoration.LineThrough else null,
                        )
                    }
                    Text(
                        buildList {
                            add(task.group.label.ifBlank { "No group" })
                            task.trackerName?.let { add(it) }
                            if (task.assignees.isNotEmpty()) add(task.assignees.joinToString(", "))
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (task.unavailable) {
                        Text("The tracker no longer answers for this task.", style = MaterialTheme.typography.bodySmall)
                    }
                    state.detail.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                    state.detail.lastOutcome?.let { outcome ->
                        Text("Last: " + listOfNotNull(outcome.host, outcome.name, outcome.branch).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                        outcome.summary?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    TaskUrlButton(task.url)
                    TaskActions(state, handlers)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Sessions",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                if (state.sessions.isEmpty()) {
                    Text(
                        "No session has worked on this yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(state.sessions, key = { it.link.linkId }) { line ->
                TaskSessionRow(line, onOpen = handlers.onOpen)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskActions(state: WorkTaskUiState, handlers: WorkTaskHandlers) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.liveSessionId?.let { id ->
            Button(onClick = { handlers.onOpen(id) }) { Text("Open") }
        }
        if (state.canStart) OutlinedButton(onClick = handlers.onStartHere, enabled = !state.busy) { Text("Start here") }
    }
    if (state.canContinue) {
        if (state.resumeHosts.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (host in state.resumeHosts) {
                    FilterChip(selected = host == state.resumeHost, onClick = { handlers.onResumeHost(host) }, label = { Text(host) })
                }
            }
        }
        Button(onClick = handlers.onContinue, enabled = !state.busy) {
            Text(state.resumeHost?.let { "Continue on $it" } ?: "Continue")
        }
    }
}

@Composable
private fun TaskSessionRow(line: TaskSessionLine, onOpen: (Long) -> Unit) {
    val link = line.link
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (link.state == LinkState.ACTIVE) StatusDot(link.claudeStatus, stuckKind = null) else Spacer(Modifier.width(10.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(linkLine(link), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (link.why.isNotBlank()) {
                Text(
                    link.why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (link.crossOrg) {
                Text("Linked across organisations", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        val id = link.sessionId
        if (line.canOpen && id != null) TextButton(onClick = { onOpen(id) }) { Text("Open") }
    }
}

/** Only an `http(s)` URL is offered: the tracker's text is not trusted to name a scheme. */
@Composable
private fun TaskUrlButton(url: String?) {
    val safe = url?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return
    val uri = LocalUriHandler.current
    TextButton(onClick = { runCatching { uri.openUri(safe) } }) { Text("Open in browser") }
}
