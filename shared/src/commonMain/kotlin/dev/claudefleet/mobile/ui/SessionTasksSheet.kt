package dev.claudefleet.mobile.ui

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.claudefleet.mobile.ui.components.DangerTextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
import dev.claudefleet.mobile.ui.kit.InlineLoading

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
    val onShareAcrossOrgs: () -> Unit = {},
    val onDismissCrossOrg: () -> Unit = {},
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
            Text("Tasks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() })
            if (!state.connected) {
                Text(
                    "Offline — nothing can be changed until the hub is back; nothing is queued.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (state.error != null && state.conflict) ReloadRow(handlers.onReload)
            InlineLoading(waiting = state.loading || state.busy)
            val crossOrg = state.crossOrg
            if (crossOrg != null) {
                CrossOrgPanel(crossOrg, state, handlers)
            } else if (state.addOpen) {
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

internal fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    links: List<WorkTaskLink>,
    state: SessionTasksUiState,
    handlers: SessionTasksHandlers,
    /** The New layout's ticket sheet says Unlink (redesign 14.15). */
    removeLabel: String = "Remove",
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
    items(links, key = { "l-$title-${it.linkId}" }) { link -> TaskLinkRow(link, state, handlers, removeLabel) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskLinkRow(link: WorkTaskLink, state: SessionTasksUiState, handlers: SessionTasksHandlers, removeLabel: String = "Remove") {
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
                    if (state.canRemove) DangerTextButton(onClick = { handlers.onRemove(link) }, enabled = enabled) { Text(removeLabel) }
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
internal fun AddTask(state: SessionTasksUiState, handlers: SessionTasksHandlers) {
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
                // Another org's ticket stays listed — so it is not "missing" —
                // but says why the phone will not link it (decision D15).
                val otherOrg = state.otherOrg[ticket.id]
                ListItem(
                    modifier = Modifier.clickable(enabled = !state.busy && state.connected) { handlers.onAdd(ticket) },
                    leadingContent = { ticket.statusCategory?.let { WorkStatusDot(it) } },
                    headlineContent = {
                        Text(
                            ticket.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (otherOrg != null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                        )
                    },
                    supportingContent = {
                        Column {
                            if (ticket.title.isNotBlank()) Text(ticket.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (otherOrg != null) {
                                Text(
                                    "$otherOrg — another organisation than this session" +
                                        (state.sessionOrgName?.let { " ($it)" } ?: "") + ". Tap to choose.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * A task and this session are in different organisations: say which, and
 * offer the two ways on. **Link anyway** shares the task across them (sent
 * with `force_cross_org`, only from here). **Move this session** is an org
 * rule, the hub master's to add, so the command is shown to copy.
 */
@Composable
internal fun CrossOrgPanel(choice: CrossOrgChoice, state: SessionTasksUiState, handlers: SessionTasksHandlers) {
    val clipboard = LocalClipboardManager.current
    val task = choice.taskOrgName ?: "another organisation"
    val session = choice.sessionOrgName ?: "a different one"
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Another organisation", style = MaterialTheme.typography.titleMedium)
        Text("${choice.what} is in $task; this session is in $session. What should happen?", style = MaterialTheme.typography.bodyMedium)

        Text("Share it across organisations", style = MaterialTheme.typography.titleSmall)
        Text(
            "Link it anyway. The session stays in $session and the link is marked as crossing organisations.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (choice.canShare) {
            Button(onClick = handlers.onShareAcrossOrgs, enabled = !state.busy && state.connected) { Text("Link anyway") }
        } else {
            Text(
                "Not from this phone: it needs a full token, connected, on a hub that allows it. Link it from the desktop's Tasks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val command = choice.moveCommand
        Text("Move this session to $task", style = MaterialTheme.typography.titleSmall)
        if (command != null) {
            Text(
                "An org rule does it; only the hub's owner can add one. Run on the hub" +
                    (choice.moveCovers?.let { " — it moves $it" } ?: "") + ":",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(command)) }) { Text("Copy command") }
        }
        Text(
            "Or on the desktop: Settings → Work → Organisations. Once the session is in $task, add the task again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = handlers.onDismissCrossOrg) { Text("Cancel") }
    }
}
