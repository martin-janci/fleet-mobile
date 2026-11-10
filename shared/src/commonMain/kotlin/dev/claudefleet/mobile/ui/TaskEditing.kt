package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ISO_DATE
import dev.claudefleet.mobile.model.PromoteTarget
import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.model.epochDayOf
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
    if (!ISO_DATE.matches(s)) return "A date is YYYY-MM-DD, like 2026-10-23."
    // The calendar is [epochDayOf]'s; 1970 on, as the hub keeps it.
    return if (s.take(4).toInt() >= 1970 && epochDayOf(s) != null) null else "That day does not exist."
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
    /** One of [TASK_PRIORITIES]' values; `""` clears it. */
    val priority: String? = null,
    /** Empty clears them. */
    val labels: List<String>? = null,
    /** 0 clears it (and its unit). */
    val estimate: Double? = null,
    /** `points | hours`: with [estimate], or alone to re-unit the estimate the task has. */
    val estimateUnit: String? = null,
) {
    val isEmpty: Boolean get() =
        title == null && notes == null && assignees == null && dueAt == null && priority == null &&
            labels == null && estimate == null && estimateUnit == null
}

/** The hub's limits on labels and estimates (claude-fleet `work_tasks.rs`). */
internal const val LABELS_MAX = 20
internal const val LABEL_MAX_CHARS = 40
internal const val ESTIMATE_MAX = 1000.0

/** What an estimate counts, as the sheet offers it. */
internal val ESTIMATE_UNITS: List<Pair<String, String>> = listOf("points" to "Points", "hours" to "Hours")

/** An estimate as its field starts: `""` for none, `3` not `3.0`. */
internal fun estimateFieldText(estimate: Double): String = when {
    estimate <= 0.0 -> ""
    estimate == kotlin.math.floor(estimate) -> estimate.toLong().toString()
    else -> estimate.toString()
}

/**
 * The estimate field as a number: 0 when blank (no estimate), null when it is
 * not a number. A decimal comma ("2,5"), what many keypads offer, is a point.
 */
internal fun parseEstimate(raw: String): Double? = raw.trim().replace(',', '.').let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() }

/** Why the labels cannot be saved, as the hub checks them; null when they can. Said under the Labels field. */
internal fun taskLabelsError(labels: String): String? {
    val tags = parseAssigneesText(labels)
    if (tags.size > LABELS_MAX) return "At most $LABELS_MAX labels."
    // Characters as the hub counts them (code points: an emoji is one).
    if (tags.any { t -> t.count { !it.isLowSurrogate() } > LABEL_MAX_CHARS }) return "A label is at most $LABEL_MAX_CHARS characters."
    if (tags.any { t -> t.any { it.isISOControl() } }) return "A label has no control characters."
    return null
}

/** Why the estimate cannot be saved, as the hub checks it; null when it can. Said under the Estimate field. */
internal fun taskEstimateError(estimate: String): String? {
    val e = parseEstimate(estimate)
    return if (e == null || !e.isFinite() || e < 0.0 || e > ESTIMATE_MAX) "An estimate is a number from 0 to ${ESTIMATE_MAX.toLong()}." else null
}

/** The priorities a person sets, most pressing first, `""` for none (the desktop's Edit task). */
internal val TASK_PRIORITIES: List<Pair<String, String>> = listOf(
    "" to "None",
    "urgent" to "Urgent",
    "high" to "High",
    "medium" to "Medium",
    "low" to "Low",
)

/** What a form holds against what it started from: only a changed field is sent. */
internal fun taskEditOf(
    title: String,
    notes: String,
    assignees: String,
    due: String,
    before: TaskEditFields,
    notesLocked: Boolean,
    priority: String = before.priority,
    labels: String = before.labels.joinToString(", "),
    estimate: String = estimateFieldText(before.estimate),
    unit: String = before.estimateUnit,
    /**
     * The hub's `work_link` takes priority, labels and estimate
     * (`caps.accepts(WORK_LINK, "priority")`). An older one would drop them
     * and call the edit saved, so without it they are never sent.
     */
    sizes: Boolean = true,
): TaskEdit {
    // The desktop's rule: a new size carries its unit; the same size in
    // another unit re-units it; a cleared one sends 0 alone.
    val size = parseEstimate(estimate) ?: before.estimate
    val sizeChanged = size != before.estimate
    return TaskEdit(
        title = title.trim().takeIf { it != before.title },
        notes = notes.trim().takeIf { !notesLocked && it != before.notes.trim() },
        assignees = parseAssigneesText(assignees).takeIf { it != before.assignees },
        dueAt = due.trim().takeIf { it != before.dueAt },
        priority = priority.takeIf { sizes && it != before.priority },
        labels = parseAssigneesText(labels).takeIf { sizes && it != before.labels },
        estimate = size.takeIf { sizes && sizeChanged },
        estimateUnit = unit.takeIf { sizes && size > 0.0 && (sizeChanged || it != before.estimateUnit) },
    )
}

/** [edit] as a hub that takes no priority, labels or estimate gets it: those three left out. */
internal fun TaskEdit.withoutSizes(): TaskEdit = copy(priority = null, labels = null, estimate = null, estimateUnit = null)

/** The fields of a task as the Edit sheet starts them. */
data class TaskEditFields(
    val title: String = "",
    val notes: String = "",
    val assignees: List<String> = emptyList(),
    val dueAt: String = "",
    /** `urgent | high | medium | low`, or `""` for none. */
    val priority: String = "",
    val labels: List<String> = emptyList(),
    /** 0: no estimate. */
    val estimate: Double = 0.0,
    /** `points | hours`; points when the task has no estimate. */
    val estimateUnit: String = "points",
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
 * **Edit task**: title, description, assignees, due date, priority, labels
 * and estimate of a
 * local item. A delegated job's description is its prompt: shown, not
 * editable. Save is off until something changed and the fields are valid.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    /** Priority, labels and estimate are offered (and sent): the hub's `work_link` takes them. */
    sizes: Boolean = true,
) {
    // What the sheet opened on, once: [before] follows every re-read of the
    // task, and diffing against a later one would send this sheet's stale
    // copy of a field someone else changed meanwhile (the desktop's `initial`).
    val start = remember { before }
    var title by remember { mutableStateOf(start.title) }
    var notes by remember { mutableStateOf(start.notes) }
    var assignees by remember { mutableStateOf(start.assignees.joinToString(", ")) }
    var due by remember { mutableStateOf(start.dueAt) }
    var priority by remember { mutableStateOf(start.priority) }
    var labels by remember { mutableStateOf(start.labels.joinToString(", ")) }
    var estimate by remember { mutableStateOf(estimateFieldText(start.estimate)) }
    var unit by remember { mutableStateOf(start.estimateUnit) }
    val titleError = taskTitleError(title)
    val dueError = dueDateError(due)
    val labelsError = if (sizes) taskLabelsError(labels) else null
    val estimateError = if (sizes) taskEstimateError(estimate) else null
    val edit = taskEditOf(title, notes, assignees, due, start, notesLocked, priority, labels, estimate, unit, sizes)
    val canSave = connected && !busy && titleError == null && dueError == null && labelsError == null && estimateError == null && !edit.isEmpty
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
            if (sizes) {
                Text("Priority", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in TASK_PRIORITIES) {
                        FilterChip(selected = priority == value, onClick = { priority = value }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = labels,
                    onValueChange = { labels = it },
                    label = { Text("Labels") },
                    placeholder = { Text("Comma-separated") },
                    singleLine = true,
                    isError = labelsError != null,
                    supportingText = labelsError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = estimate,
                    onValueChange = { estimate = it },
                    label = { Text("Estimate") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = estimateError != null,
                    supportingText = { Text(estimateError ?: "Blank: no estimate.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in ESTIMATE_UNITS) {
                        FilterChip(selected = unit == value, onClick = { unit = value }, label = { Text(label) })
                    }
                }
            }
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
/**
 * The container a Promote sheet starts with on [target]: the only one when a
 * container is required and there is just one, else none picked.
 */
internal fun initialContainer(target: PromoteTarget?): String =
    if (target?.containerRequired == true && target.choices.size == 1) target.choices[0].first else ""

/** Create ticket may be pressed: a target, and a container where one is required. */
internal fun canPromoteTo(target: PromoteTarget?, container: String): Boolean =
    target != null && (target.parentKey != null || !target.containerRequired || container.isNotBlank())

/**
 * **Promote to ticket**: fleet's own task becomes a ticket — the same row, so
 * its sessions, comments, subtasks and sprint stay with it and its fleet key
 * still finds it. A subtask of a ticket goes under that ticket (nothing to
 * pick); a task at the top picks a tracker and where in it, as the desktop's
 * dialog does.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PromoteSheet(
    title: String,
    targets: List<PromoteTarget>,
    busy: Boolean,
    connected: Boolean,
    error: Friendly?,
    onDismissError: () -> Unit,
    onCancel: () -> Unit,
    onPromote: (PromoteTarget, String?) -> Unit,
) {
    var trackerId by remember { mutableStateOf(targets.firstOrNull()?.trackerId) }
    val target = targets.firstOrNull { it.trackerId == trackerId }
    var container by remember(trackerId) { mutableStateOf(initialContainer(target)) }
    val canCreate = connected && !busy && canPromoteTo(target, container)
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Promote to a ticket", style = MaterialTheme.typography.titleLarge)
            ErrorBanner(error, onDismiss = onDismissError)
            Text(
                "“$title” becomes a ticket. Its sessions, comments, subtasks and sprint stay with it; its fleet key still finds it.",
                style = MaterialTheme.typography.bodySmall,
            )
            val under = target?.parentKey
            if (target != null && under != null) {
                Text("A subtask of $under in ${target.tracker}.", style = MaterialTheme.typography.bodyMedium)
            } else {
                if (targets.size > 1) {
                    Text("Tracker", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (t in targets) {
                            FilterChip(selected = t.trackerId == trackerId, onClick = { trackerId = t.trackerId }, label = { Text(t.tracker) })
                        }
                    }
                } else if (target != null) {
                    Text("In ${target.tracker}.", style = MaterialTheme.typography.bodyMedium)
                }
                if (target != null && target.choices.isNotEmpty()) {
                    Text(target.containerLabel, style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!target.containerRequired) {
                            FilterChip(selected = container.isEmpty(), onClick = { container = "" }, label = { Text("None: my tasks") })
                        }
                        for ((id, name) in target.choices) {
                            FilterChip(selected = container == id, onClick = { container = id }, label = { Text(name) })
                        }
                    }
                } else if (target != null && target.containerRequired) {
                    // A GitHub tracker narrowed to no repositories: any in its scope.
                    OutlinedTextField(
                        value = container,
                        onValueChange = { container = it },
                        label = { Text(target.containerLabel) },
                        placeholder = { Text(if (target.provider == "github") "owner/repo" else "") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Text("The description goes with it, signed with your name. The tracker numbers it.", style = MaterialTheme.typography.bodySmall)
            if (!connected) {
                Text("Offline — nothing is sent until the hub is back; nothing is queued.", style = MaterialTheme.typography.bodySmall)
            }
            SheetButtons(
                verb = if (busy) "Creating…" else "Create ticket",
                enabled = canCreate,
                onCancel = onCancel,
                onConfirm = { target?.let { onPromote(it, container.ifBlank { null }) } },
            )
        }
    }
}

@Composable
private fun SheetButtons(verb: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Button(onClick = onConfirm, enabled = enabled) { Text(verb) }
    }
}
