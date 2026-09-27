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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.WorkStatusDot

/** What the session screen can do about its work — every one a no-op until wired. */
data class SessionWorkHandlers(
    val onOpen: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onReject: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onSetWork: (String) -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onHandover: () -> Unit = {},
    /** *Name this work…*: a title, and a key or null. */
    val onNameWork: (String, String?) -> Unit = { _, _ -> },
    /** *Rename…* the session's local work. */
    val onRenameWork: (String) -> Unit = {},
)

/**
 * The small sheet behind a session's ticket chip: the ticket, why the session
 * is linked to it, and — only when this token may and this hub can — the
 * decisions. Every string from the tracker is drawn as plain text.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkTicketSheet(state: SessionWorkUiState, handlers: SessionWorkHandlers) {
    val shown = state.chip ?: return
    val suggestion = state.work == null
    var showRename by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                shown.statusCategory?.let { WorkStatusDot(it) }
                Text(
                    text = shown.label,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (shown.unavailable) TextDecoration.LineThrough else null,
                )
                shown.statusName?.let {
                    Text(" · $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (shown.title.isNotBlank() && shown.key != null) {
                Text(shown.title, style = MaterialTheme.typography.bodyLarge)
            }
            if (shown.unavailable) {
                Text("The tracker no longer answers for this ticket.", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = (if (suggestion) "Suggested: " else "Why: ") + workWhy(shown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (suggestion && shown.suggestions > 1) {
                Text("${shown.suggestions} suggestions — decide the rest on the desktop.", style = MaterialTheme.typography.bodySmall)
            }
            OpenTicketButton(shown)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canConfirm) Button(onClick = handlers.onConfirm, enabled = !state.busy) { Text("Confirm") }
                if (state.canReject) OutlinedButton(onClick = handlers.onReject, enabled = !state.busy) { Text("Not this") }
                if (state.canClear) OutlinedButton(onClick = handlers.onClear, enabled = !state.busy) { Text("Clear") }
                if (state.canRename && !suggestion) {
                    OutlinedButton(onClick = { showRename = true }, enabled = !state.busy) { Text("Rename…") }
                }
                if (state.canHandover && !suggestion) {
                    OutlinedButton(onClick = handlers.onHandover, enabled = !state.busy) { Text("Ask for a handover") }
                }
            }
            state.handover?.let {
                Text(it.sentence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showRename && state.canRename) {
        NameWorkDialog(
            initialTitle = shown.title,
            withKey = false,
            onConfirm = { title, _ -> showRename = false; handlers.onRenameWork(title) },
            onDismiss = { showRename = false },
        )
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
 * *Name this work…* (with an optional key) and *Rename…* (the title only):
 * the confirm button stays off for a title the hub would refuse, and the
 * reason is shown under the field.
 */
@Composable
fun NameWorkDialog(
    initialTitle: String = "",
    withKey: Boolean,
    onConfirm: (title: String, key: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var key by remember { mutableStateOf("") }
    val problem = localWorkTitleProblem(title)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (withKey) "Name this work" else "Rename work") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("What this work is") },
                    singleLine = true,
                    isError = problem != null && title.isNotEmpty(),
                    supportingText = { if (problem != null && title.isNotEmpty()) Text(problem) },
                )
                if (withKey) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("Key (optional)") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, key.takeIf { withKey && it.isNotBlank() }) },
                enabled = problem == null,
            ) { Text(if (withKey) "Name" else "Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
