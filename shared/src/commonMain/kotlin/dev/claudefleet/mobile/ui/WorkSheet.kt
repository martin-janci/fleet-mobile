package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.TicketCardBody
import dev.claudefleet.mobile.ui.components.WorkStatusDot

/** What the session screen can do about its work — every one a no-op until wired. */
data class SessionWorkHandlers(
    val onOpen: () -> Unit = {},
    val onClose: () -> Unit = {},
    /** Confirm the suggestion drawn with this link id — see [SuggestionRow]. */
    val onConfirm: (linkId: Long) -> Unit = {},
    /** "Not this" for the suggestion drawn with this link id. */
    val onReject: (linkId: Long) -> Unit = {},
    val onClear: () -> Unit = {},
    val onSetWork: (String) -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onHandover: () -> Unit = {},
    /** **Name this work…**: a title, and an optional key. */
    val onNameWork: (title: String, key: String?) -> Unit = { _, _ -> },
    /** **Rename** the session's local work. */
    val onRenameWork: (String) -> Unit = {},
)

/**
 * The small sheet behind a session's ticket chip: the ticket, why the session
 * is linked to it, and — only when this token may and this hub can — the
 * decisions. Every string from the tracker is drawn as plain text.
 *
 * A suggestion's Confirm and Not this are drawn only inside its own block,
 * beside its key, title and reasons ([SuggestionRow]); when the session also
 * has confirmed work, that work is drawn as the current one and the
 * suggestion as a separate block under it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkTicketSheet(state: SessionWorkUiState, handlers: SessionWorkHandlers) {
    if (state.chip == null) return
    val current = state.work
    val suggestion = state.suggestion
    var renaming by remember { mutableStateOf(false) }
    if (renaming && current != null) {
        WorkTitleDialog(
            heading = "Rename work",
            initialTitle = current.title,
            askKey = false,
            confirmLabel = "Rename",
            onConfirm = { title, _ -> renaming = false; handlers.onRenameWork(title) },
            onDismiss = { renaming = false },
        )
    }
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (current != null) {
                WorkHeading(current, MaterialTheme.typography.titleMedium)
                ErrorBanner(state.error, onDismiss = handlers.onDismissError)
                WorkDetails(current, "Why: " + workWhy(current), state.workTrouble)
                // The ticket's card (M10.5): read-only, with Copy, never Send.
                state.card?.let { TicketCardBody(it) }
                OpenTicketButton(current)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.canClear) OutlinedButton(onClick = handlers.onClear, enabled = !state.busy) { Text("Clear") }
                    if (state.canHandover) {
                        OutlinedButton(onClick = handlers.onHandover, enabled = !state.busy) { Text("Ask for a handover") }
                    }
                    if (state.canRenameWork) {
                        OutlinedButton(onClick = { renaming = true }, enabled = !state.busy) { Text("Rename") }
                    }
                }
                state.handover?.let {
                    Text(it.sentence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                suggestion?.let {
                    HorizontalDivider()
                    Text("Also suggested", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    WorkHeading(it.work, MaterialTheme.typography.titleSmall)
                    WorkDetails(it.work, "Why suggested: " + it.why, it.trouble)
                    OpenTicketButton(it.work)
                    SuggestionDecisions(it, state.busy, handlers)
                }
            } else if (suggestion != null) {
                WorkHeading(suggestion.work, MaterialTheme.typography.titleMedium)
                ErrorBanner(state.error, onDismiss = handlers.onDismissError)
                WorkDetails(suggestion.work, "Suggested: " + suggestion.why, suggestion.trouble)
                state.card?.let { TicketCardBody(it) }
                OpenTicketButton(suggestion.work)
                SuggestionDecisions(suggestion, state.busy, handlers)
            }
        }
    }
}

/** A ticket's key, status dot and tracker status, in one line. */
@Composable
private fun WorkHeading(work: WorkSummary, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        work.statusCategory?.let { WorkStatusDot(it) }
        Text(
            text = work.label,
            style = style,
            textDecoration = if (work.unavailable) TextDecoration.LineThrough else null,
        )
        work.statusName?.let {
            Text(" · $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The ticket's title, what is wrong with it or its tracker ([trouble], the
 * hub's reasons as plain text), and [why] it is here.
 */
@Composable
private fun WorkDetails(work: WorkSummary, why: String, trouble: List<String>) {
    if (work.title.isNotBlank() && work.key != null) {
        Text(work.title, style = MaterialTheme.typography.bodyLarge)
    }
    for (line in trouble) {
        Text(line, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        text = why,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Confirm and Not this for [row] — bound to its link id, and drawn only in
 * the block that shows [row]'s ticket and reasons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionDecisions(row: SuggestionRow, busy: Boolean, handlers: SessionWorkHandlers) {
    if (row.work.suggestions > 1) {
        Text("${row.work.suggestions} suggestions — decide the rest on the desktop.", style = MaterialTheme.typography.bodySmall)
    }
    if (!row.canConfirm && !row.canReject) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (row.canConfirm) Button(onClick = { handlers.onConfirm(row.linkId) }, enabled = !busy) { Text("Confirm") }
        if (row.canReject) OutlinedButton(onClick = { handlers.onReject(row.linkId) }, enabled = !busy) { Text("Not this") }
    }
}

/** Only an `http(s)` URL is offered: the tracker's text is not trusted to name a scheme. */
@Composable
private fun OpenTicketButton(work: WorkSummary) {
    val url = work.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return
    val uri = LocalUriHandler.current
    TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text("Open in browser") }
}

/** *Set work…*: a key such as `PAY-7`, or a pasted ticket URL. */
@Composable
fun SetWorkDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set work") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Key or ticket URL") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * **Name this work…** and **Rename**: a title for local work (claude-fleet
 * M11.1; on the phone since M13.4a), and — when naming — an optional key.
 * The hub owns the rules; the view model checks the title first so a bad
 * one is said at once.
 */
@Composable
fun WorkTitleDialog(
    heading: String,
    initialTitle: String,
    askKey: Boolean,
    confirmLabel: String,
    onConfirm: (title: String, key: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("What is this work?") },
                    singleLine = true,
                    supportingText = { Text("${title.trim().length} / $WORK_TITLE_MAX") },
                    isError = title.trim().length > WORK_TITLE_MAX,
                )
                if (askKey) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("Key (optional), like OPS-1") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, key.takeIf { askKey && it.isNotBlank() }) },
                enabled = title.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
