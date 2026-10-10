package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.ui.components.ErrorBanner

/*
 * Fleet's own tasks on the phone (claude-fleet: shared work context, task
 * editing): a new task, a person's edit of one, and its status. Only a local
 * item is offered any of it — a tracker's ticket is its tracker's to change —
 * and every form is a bottom sheet with Cancel and the verb at the thumb, the
 * board rule for the phone's forms.
 */

/** The three statuses a person sets, in board order, as the desktop words them. */
internal val TASK_STATUSES: List<Pair<StatusCategory, String>> = listOf(
    StatusCategory.Todo to "To do",
    StatusCategory.InProgress to "In progress",
    StatusCategory.Done to "Done",
)

/** Longest title the hub keeps for a local item (`LOCAL_WORK_TITLE_MAX_CHARS`). */
internal const val TASK_TITLE_MAX = 120

/**
 * What is wrong with a typed due date, or null when it is fine: blank (no
 * date) or a real calendar day as `YYYY-MM-DD` — the hub's own rule, said
 * before the round trip.
 */
internal fun dueDateError(raw: String): String? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(s) ?: return "A date is YYYY-MM-DD, like 2026-10-23."
    val (y, mo, d) = m.destructured.toList().map { it.toInt() }
    val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    return if (y in 1970..9999 && mo in 1..12 && d in 1..days[mo - 1]) null else "That day does not exist."
}

/** What is wrong with a typed title, or null. */
internal fun taskTitleError(raw: String): String? = when {
    raw.isBlank() -> "A title is required."
    raw.trim().length > TASK_TITLE_MAX -> "A title is at most $TASK_TITLE_MAX characters."
    else -> null
}

/** `"Ana, bo,, ana"` → `["Ana", "bo"]`: trimmed, blanks and repeats (any case) dropped — the desktop's rule. */
internal fun parseAssigneesText(raw: String): List<String> {
    val seen = mutableSetOf<String>()
    return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
}

/** A person's edit of a task: each field null is left as it is. */
data class TaskEdit(
    val title: String? = null,
    val notes: String? = null,
    val assignees: List<String>? = null,
    val dueAt: String? = null,
) {
    val isEmpty: Boolean get() = title == null && notes == null && assignees == null && dueAt == null
}

/** What a form holds against what it started from: only a changed field is sent. */
internal fun taskEditOf(
    title: String,
    notes: String,
    assignees: String,
    due: String,
    before: TaskEditFields,
    notesLocked: Boolean,
): TaskEdit = TaskEdit(
    title = title.trim().takeIf { it != before.title },
    notes = notes.trim().takeIf { !notesLocked && it != before.notes.trim() },
    assignees = parseAssigneesText(assignees).takeIf { it != before.assignees },
    dueAt = due.trim().takeIf { it != before.dueAt },
)

/** The fields of a task as the Edit sheet starts them. */
data class TaskEditFields(
    val title: String = "",
    val notes: String = "",
    val assignees: List<String> = emptyList(),
    val dueAt: String = "",
)

/** The three statuses as chips: the current one selected, a tap sets another. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskStatusChips(current: StatusCategory?, enabled: Boolean, onPick: (StatusCategory) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((status, label) in TASK_STATUSES) {
            FilterChip(
                selected = current == status,
                enabled = enabled,
                onClick = { if (current != status) onPick(status) },
                label = { Text(label) },
            )
        }
    }
}

/**
 * **Edit task**: title, description, assignees and due date of a local item.
 * A delegated job's description is its prompt: shown, not editable. Save is
 * off until something changed and the fields are valid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskEditSheet(
    before: TaskEditFields,
    notesLocked: Boolean,
    busy: Boolean,
    connected: Boolean,
    error: Friendly?,
    onDismissError: () -> Unit,
    onCancel: () -> Unit,
    onSave: (TaskEdit) -> Unit,
) {
    var title by remember { mutableStateOf(before.title) }
    var notes by remember { mutableStateOf(before.notes) }
    var assignees by remember { mutableStateOf(before.assignees.joinToString(", ")) }
    var due by remember { mutableStateOf(before.dueAt) }
    val titleError = taskTitleError(title)
    val dueError = dueDateError(due)
    val edit = taskEditOf(title, notes, assignees, due, before, notesLocked)
    val canSave = connected && !busy && titleError == null && dueError == null && !edit.isEmpty
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Edit task", style = MaterialTheme.typography.titleLarge)
            ErrorBanner(error, onDismiss = onDismissError)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                isError = titleError != null,
                supportingText = titleError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Description") },
                enabled = !notesLocked,
                minLines = 3,
                supportingText = if (notesLocked) ({ Text("A delegated job's description is its prompt and stays as sent.") }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = assignees,
                onValueChange = { assignees = it },
                label = { Text("Assignees") },
                placeholder = { Text("Names, comma-separated") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = due,
                onValueChange = { due = it },
                label = { Text("Due") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
                isError = dueError != null,
                supportingText = { Text(dueError ?: "Blank: no due date.") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!connected) {
                Text("Offline — nothing is saved until the hub is back; nothing is queued.", style = MaterialTheme.typography.bodySmall)
            }
            SheetButtons(verb = if (busy) "Saving…" else "Save", enabled = canSave, onCancel = onCancel, onConfirm = { onSave(edit) })
        }
    }
}

/**
 * **New task**: a title, and optionally a description and due date. It is
 * fleet's own work, not a ticket: nothing is written to a tracker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewTaskSheet(
    busy: Boolean,
    /** "New task", or "Add subtask" under a named parent. */
    heading: String = "New task",
    blurb: String = "Fleet's own work: no ticket is made in a tracker.",
    connected: Boolean,
    error: Friendly?,
    onDismissError: () -> Unit,
    onCancel: () -> Unit,
    onCreate: (title: String, notes: String, dueAt: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    val titleError = taskTitleError(title)
    val dueError = dueDateError(due)
    val canCreate = connected && !busy && titleError == null && dueError == null
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(heading, style = MaterialTheme.typography.titleLarge)
            Text(blurb, style = MaterialTheme.typography.bodySmall)
            ErrorBanner(error, onDismiss = onDismissError)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; touched = true },
                label = { Text("Title") },
                singleLine = true,
                isError = touched && titleError != null,
                supportingText = (if (touched) titleError else null)?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Description (optional)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = due,
                onValueChange = { due = it },
                label = { Text("Due (optional)") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
                isError = dueError != null,
                supportingText = dueError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!connected) {
                Text("Offline — nothing is created until the hub is back; nothing is queued.", style = MaterialTheme.typography.bodySmall)
            }
            SheetButtons(
                verb = if (busy) "Creating…" else "Create",
                enabled = canCreate,
                onCancel = onCancel,
                onConfirm = { onCreate(title.trim(), notes.trim(), due.trim()) },
            )
        }
    }
}

/** Cancel and the verb, at the thumb. */
@Composable
private fun SheetButtons(verb: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Button(onClick = onConfirm, enabled = enabled) { Text(verb) }
    }
}
