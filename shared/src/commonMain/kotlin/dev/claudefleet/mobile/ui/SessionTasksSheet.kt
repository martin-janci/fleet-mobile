package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.WorkTaskLink
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.WorkStatusDot

/** Everything a session's *Tasks* section reports. */
data class SessionTasksHandlers(
    val onOpen: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onReload: () -> Unit = {},
    val onOpenTask: (WorkTaskLink) -> Unit = {},
    val onMakePrimary: (WorkTaskLink) -> Unit = {},
    val onRemove: (WorkTaskLink) -> Unit = {},
    val onConfirm: (WorkTaskLink) -> Unit = {},
    val onReject: (WorkTaskLink) -> Unit = {},
    val onOpenAdd: () -> Unit = {},
    val onCloseAdd: () -> Unit = {},
    val onAddQuery: (String) -> Unit = {},
    val onAdd: (Ticket) -> Unit = {},
    val onAddTyped: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * A session's *Tasks*: every link, grouped active (primary first, ★),
 * suggested and past; **Make primary**, **Remove**, **Add task…** (a
 * searchable list — never drag and drop) and **Open task**. Buttons exist
 * only for a token and hub that allow them, and are disabled while not
 * connected: nothing queues offline.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionTasksSheet(state: SessionTasksUiState, handlers: SessionTasksHandlers) {
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Tasks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            if (!state.connected) {
                Text(
                    "Offline — nothing can be changed until the hub is back; nothing is queued.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (state.error != null && state.conflict) ReloadRow(handlers.onReload)
            if (state.loading || state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (state.addOpen) {
                AddTask(state, handlers)
            } else {
                if (state.canAdd) {
                    OutlinedButton(onClick = handlers.onOpenAdd, enabled = !state.busy, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text("Add task…")
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    section("Active", state.active, state, handlers)
                    section("Suggested", state.suggested, state, handlers)
                    section("Past", state.past, state, handlers)
                    if (state.loaded && state.active.isEmpty() && state.suggested.isEmpty() && state.past.isEmpty()) {
                        item(key = "none") {
                            Text("This session is not linked to any task.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    links: List<WorkTaskLink>,
    state: SessionTasksUiState,
    handlers: SessionTasksHandlers,
) {
    if (links.isEmpty()) return
    item(key = "h-$title") {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    items(links, key = { "l-$title-${it.linkId}" }) { link -> TaskLinkRow(link, state, handlers) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskLinkRow(link: WorkTaskLink, state: SessionTasksUiState, handlers: SessionTasksHandlers) {
    val task = link.task
    val enabled = !state.busy
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        ListItem(
            modifier = Modifier.clickable(enabled = task != null) { handlers.onOpenTask(link) },
            leadingContent = { task?.statusCategory?.let { WorkStatusDot(it) } },
            headlineContent = {
                Text(
                    (if (link.primary && link.state == LinkState.Active) "★ " else "") + (task?.label ?: "Task"),
                    style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (task?.unavailable == true) TextDecoration.LineThrough else null,
                )
            },
            supportingContent = {
                val line = listOfNotNull(
                    task?.title?.takeIf { it.isNotBlank() && task.key != null },
                    linkStateWords(link),
                    link.why?.takeIf { it.isNotBlank() },
                    "another organisation".takeIf { link.crossOrg },
                ).joinToString(" · ")
                Text(line, maxLines = 3, overflow = TextOverflow.Ellipsis)
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp)) {
            if (task != null) TextButton(onClick = { handlers.onOpenTask(link) }) { Text("Open task") }
            when (link.state) {
                LinkState.Active -> {
                    if (!link.primary && state.canMakePrimary) OutlinedButton(onClick = { handlers.onMakePrimary(link) }, enabled = enabled) { Text("Make primary") }
                    if (state.canRemove) TextButton(onClick = { handlers.onRemove(link) }, enabled = enabled) { Text("Remove") }
                }
                LinkState.Suggested -> {
                    if (state.canConfirm) OutlinedButton(onClick = { handlers.onConfirm(link) }, enabled = enabled) { Text("Confirm") }
                    if (state.canReject) TextButton(onClick = { handlers.onReject(link) }, enabled = enabled) { Text("Not this") }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun AddTask(state: SessionTasksUiState, handlers: SessionTasksHandlers) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.addQuery,
            onValueChange = handlers.onAddQuery,
            label = { Text("Search, or type a key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { handlers.onAddTyped() }),
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val typed = state.addQuery.trim()
            if (typed.isNotEmpty()) {
                Button(onClick = handlers.onAddTyped, enabled = !state.busy && state.connected) { Text("Link “$typed”") }
            }
            TextButton(onClick = handlers.onCloseAdd) { Text("Cancel") }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(state.addCandidates, key = { it.id }) { ticket ->
                ListItem(
                    modifier = Modifier.clickable(enabled = !state.busy && state.connected) { handlers.onAdd(ticket) },
                    leadingContent = { ticket.statusCategory?.let { WorkStatusDot(it) } },
                    headlineContent = { Text(ticket.label, style = MaterialTheme.typography.titleSmall) },
                    supportingContent = {
                        if (ticket.title.isNotBlank()) Text(ticket.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
}
