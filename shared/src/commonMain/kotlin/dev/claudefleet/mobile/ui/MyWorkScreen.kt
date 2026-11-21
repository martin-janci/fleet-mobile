package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.ui.components.DangerTextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.IdOrWord
import dev.claudefleet.mobile.model.WorkFacetId
import dev.claudefleet.mobile.model.fold
import dev.claudefleet.mobile.model.WorkRule
import dev.claudefleet.mobile.model.WorkTreeFilters
import dev.claudefleet.mobile.model.WorkView
import dev.claudefleet.mobile.ui.components.WorkStatusDot

/** Everything the My work tab reports. */
data class MyWorkHandlers(
    val onOpenTask: (String) -> Unit = {},
    /** Open a task's live session by its id: a row's Session button, or a tap under [WorkTapChoice.SESSION]. */
    val onOpenSession: (Long) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onReload: () -> Unit = {},
    val onToggleSection: (String) -> Unit = {},
    val onLoadMore: (String) -> Unit = {},
    val onToggleSearch: () -> Unit = {},
    val onSetQuery: (String) -> Unit = {},
    val onOpenFilters: () -> Unit = {},
    val onCloseFilters: () -> Unit = {},
    val onSetOrg: (IdOrWord?) -> Unit = {},
    val onSetTracker: (IdOrWord?) -> Unit = {},
    val onSetStatus: (String?) -> Unit = {},
    val onSetHas: (String?) -> Unit = {},
    val onSetIteration: (String?) -> Unit = {},
    val onSetEpic: (String?) -> Unit = {},
    val onSetItemType: (String?) -> Unit = {},
    val onSetSort: (String?) -> Unit = {},
    val onSetPriority: (String?) -> Unit = {},
    val onSetLabel: (String?) -> Unit = {},
    val onToggleMine: () -> Unit = {},
    val onToggleReview: () -> Unit = {},
    val onClearFilters: () -> Unit = {},
    /** One chip's ✕ in the strip, or a sheet group's *Any*. */
    val onClearFacet: (WorkFacetId) -> Unit = {},
    /** *Show archived* (the sheet's switch, the list's last row), or hide them again. */
    val onSetArchived: (Boolean) -> Unit = {},
    val onApplyView: (WorkView) -> Unit = {},
    val onSaveView: (String) -> Unit = {},
    val onUpdateView: (WorkView) -> Unit = {},
    val onDeleteView: (WorkView) -> Unit = {},
    /** Null hides the Review button — a hub without `work { review }`. */
    val onOpenReview: (() -> Unit)? = null,
    /** Work's Pull requests sheet (redesign 6.7); null on a hub without `prs`. */
    val onOpenPullRequests: (() -> Unit)? = null,
    /** Null hides *Rules* — a hub without `work { rules }`. */
    val onOpenRules: (() -> Unit)? = null,
    val onCloseRules: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** **New task**: its sheet, and Create (title, description, due date). */
    val onOpenNewTask: () -> Unit = {},
    val onCloseNewTask: () -> Unit = {},
    val onCreateTask: (String, String, String) -> Unit = { _, _, _ -> },
    val onDismissNewTaskError: () -> Unit = {},
)

/** The New task sheet over My work (either layout), while it is open. */
@Composable
internal fun NewTaskSheetFor(state: MyWorkUiState, handlers: MyWorkHandlers) {
    if (!state.newTaskOpen) return
    NewTaskSheet(
        busy = state.newTaskBusy,
        connected = state.connected,
        error = state.newTaskError,
        onDismissError = handlers.onDismissNewTaskError,
        onCancel = handlers.onCloseNewTask,
        onCreate = handlers.onCreateTask,
    )
}

/** *Reload* beside a conflict: what the hub has now, instead of what this screen last read. */
@Composable
internal fun ReloadRow(onReload: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = onReload) { Text("Reload") }
    }
}

/** Two labels are one as the hub matches them: case and accents ignored ([fold]; the desktop's `fold(l) === fold(own)`). */
internal fun sameLabel(a: String?, b: String): Boolean = a != null && fold(a) == fold(b)

/** The Label chips: the page's labels, and a remembered one it does not list (so it can be seen and cleared). */
internal fun labelFilterChoices(labels: List<String>, current: String?): List<String> =
    labels + listOfNotNull(current?.takeIf { v -> labels.none { sameLabel(v, it) } })

/** "No tasks match Org: Acme, Status: Done." — the desktop's empty state. */
internal fun emptyWorkSentence(facets: String): String = "No tasks match $facets."

/**
 * One sheet of labelled chip groups — *Assigned to me* and *To review* (the
 * Work tab has one Mine chip, and To review opens the cards), then
 * Organisation, Tracker, Status, Sessions, each led by *Any* — then *Show
 * archived*, and saving the filters as a view.
 *
 * A remembered org or tracker the last page no longer lists still gets its
 * chip, selected, so a filter that is on always has a control that shows it
 * and turns it off (the Organisation group used to vanish when the page
 * listed no orgs, leaving `org: none` narrowing the tree unseen).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WorkFiltersSheet(
    state: MyWorkUiState,
    handlers: MyWorkHandlers,
) {
    var naming by remember { mutableStateOf(false) }
    val f = state.filters
    ModalBottomSheet(onDismissRequest = handlers.onCloseFilters) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = handlers.onClearFilters, enabled = !f.isEmpty || state.showArchived) { Text("Clear all") }
            }
            FilterGroup("Show") {
                ChipFlow {
                    ChoiceChip("Assigned to me", f.mine == true, handlers.onToggleMine)
                    ChoiceChip("Has something to review", f.review == true, handlers.onToggleReview)
                }
            }
            if (state.filterOrgs.isNotEmpty() || f.org != null) {
                FilterGroup("Organisation") {
                    ChipFlow {
                        ChoiceChip("Any", f.org == null, { handlers.onClearFacet(WorkFacetId.ORG) })
                        for (org in state.filterOrgs) {
                            ChoiceChip(org.name.ifBlank { "Org ${org.id}" }, f.org == IdOrWord.of(org.id), { handlers.onSetOrg(IdOrWord.of(org.id)) })
                        }
                        val stale = f.org?.id?.takeIf { id -> state.filterOrgs.none { it.id == id } }
                        if (stale != null) ChoiceChip("Org #$stale", true, { handlers.onClearFacet(WorkFacetId.ORG) })
                        ChoiceChip("Unassigned", f.org == IdOrWord.NONE, { handlers.onSetOrg(IdOrWord.NONE) })
                    }
                }
            }
            FilterGroup("Tracker") {
                ChipFlow {
                    ChoiceChip("Any", f.tracker == null, { handlers.onClearFacet(WorkFacetId.TRACKER) })
                    for (t in state.filterTrackers) {
                        ChoiceChip(t.name.ifBlank { t.provider }, f.tracker == IdOrWord.of(t.id), { handlers.onSetTracker(IdOrWord.of(t.id)) })
                    }
                    val stale = f.tracker?.id?.takeIf { id -> state.filterTrackers.none { it.id == id } }
                    if (stale != null) ChoiceChip("Tracker #$stale", true, { handlers.onClearFacet(WorkFacetId.TRACKER) })
                    ChoiceChip("Local work", f.tracker == IdOrWord.LOCAL, { handlers.onSetTracker(IdOrWord.LOCAL) })
                    ChoiceChip("Bare keys", f.tracker == IdOrWord.REF, { handlers.onSetTracker(IdOrWord.REF) })
                }
            }
            FilterGroup("Status") {
                ChipFlow {
                    ChoiceChip(WorkTreeFilters.statusLabel(WorkTreeFilters.ANY), f.status == null, { handlers.onClearFacet(WorkFacetId.STATUS) })
                    for (s in WorkTreeFilters.STATUSES) ChoiceChip(WorkTreeFilters.statusLabel(s), f.status == s, { handlers.onSetStatus(s) })
                }
            }
            val planning = state.planning
            if (planning.iterations.isNotEmpty() || f.iteration != null) {
                FilterGroup("Sprint") {
                    ChipFlow {
                        ChoiceChip("Any", f.iteration == null, { handlers.onClearFacet(WorkFacetId.ITERATION) })
                        ChoiceChip("Current sprint", f.iteration == "current", { handlers.onSetIteration("current") })
                        for (sprint in planning.iterations) {
                            ChoiceChip(sprint.name.ifBlank { "Sprint" }, f.iteration == sprint.name, { handlers.onSetIteration(sprint.name) })
                        }
                        val stale = f.iteration?.takeIf { v -> v != "current" && v != "none" && planning.iterations.none { it.name == v } }
                        if (stale != null) ChoiceChip(stale, true, { handlers.onClearFacet(WorkFacetId.ITERATION) })
                        ChoiceChip("No sprint", f.iteration == "none", { handlers.onSetIteration("none") })
                    }
                }
            }
            if (planning.epics.isNotEmpty() || f.epic != null) {
                FilterGroup("Epic") {
                    ChipFlow {
                        ChoiceChip("Any", f.epic == null, { handlers.onClearFacet(WorkFacetId.EPIC) })
                        for (e in planning.epics) {
                            val label = listOfNotNull(e.key, e.title.ifBlank { null }).joinToString(" ")
                            ChoiceChip(label, f.epic.equals(e.ref, ignoreCase = true), { handlers.onSetEpic(e.ref) })
                        }
                        val stale = f.epic?.takeIf { v -> planning.epics.none { it.ref.equals(v, ignoreCase = true) } }
                        if (stale != null) ChoiceChip(stale, true, { handlers.onClearFacet(WorkFacetId.EPIC) })
                    }
                }
            }
            if (planning.itemTypes.isNotEmpty() || f.itemType != null) {
                FilterGroup("Type") {
                    ChipFlow {
                        ChoiceChip("Any", f.itemType == null, { handlers.onClearFacet(WorkFacetId.ITEM_TYPE) })
                        val types = planning.itemTypes + listOfNotNull(f.itemType?.takeIf { v -> planning.itemTypes.none { it.equals(v, ignoreCase = true) } })
                        for (t in types) ChoiceChip(t, f.itemType.equals(t, ignoreCase = true), { handlers.onSetItemType(t) })
                    }
                }
            }
            // Only where the hub acts on it: an older one drops the filter and
            // would list everything under a chip saying otherwise.
            if (state.priorityFilter) {
                FilterGroup("Priority") {
                    ChipFlow {
                        ChoiceChip("Any", f.priority == null, { handlers.onSetPriority(null) })
                        for (p in WorkTreeFilters.PRIORITIES) {
                            ChoiceChip(WorkTreeFilters.priorityFilterLabel(p), f.priority == p, { handlers.onSetPriority(p) })
                        }
                    }
                }
            }
            if (planning.labels.isNotEmpty() || f.label != null) {
                FilterGroup("Label") {
                    ChipFlow {
                        ChoiceChip("Any", f.label == null, { handlers.onClearFacet(WorkFacetId.LABEL) })
                        for (l in labelFilterChoices(planning.labels, f.label)) ChoiceChip(l, sameLabel(f.label, l), { handlers.onSetLabel(l) })
                    }
                }
            }
            FilterGroup("Sort") {
                ChipFlow {
                    for (s in state.sorts) {
                        ChoiceChip(WorkTreeFilters.sortLabel(s), (f.sort ?: "activity") == s, { handlers.onSetSort(s.takeIf { it != "activity" }) })
                    }
                }
            }
            FilterGroup("Sessions") {
                ChipFlow {
                    ChoiceChip(WorkTreeFilters.hasLabel(WorkTreeFilters.ANY), f.has == null, { handlers.onClearFacet(WorkFacetId.HAS) })
                    for (h in WorkTreeFilters.HAS) ChoiceChip(WorkTreeFilters.hasLabel(h), f.has == h, { handlers.onSetHas(h) })
                }
            }
            FilterGroup("Include") {
                SwitchRow(
                    label = "Show archived",
                    help = "Done tasks, and tasks whose sessions are all archived, with nothing running",
                    checked = state.showArchived,
                    onToggle = { handlers.onSetArchived(!state.showArchived) },
                )
            }
            HorizontalDivider()
            FlowRow(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canSaveView) TextButton(onClick = { naming = true }) { Text("Save as view…") }
                val active = state.views.firstOrNull { it.id == state.activeViewId }
                if (active != null && state.canDeleteView) DangerTextButton(onClick = { handlers.onDeleteView(active) }) { Text("Delete “${active.name}”") }
                handlers.onOpenRules?.let { open -> TextButton(onClick = open) { Text("Placement rules") } }
            }
            if (state.viewsAvailable && state.views.isNotEmpty() && state.canSaveView) {
                val saved = state.views
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text("Update a saved view with these filters:", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (view in saved) OutlinedButton(onClick = { handlers.onUpdateView(view) }) { Text(view.name) }
                    }
                }
            }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save as view") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { naming = false; handlers.onSaveView(name) }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

/** Placement rules, read-only: they are made and changed on the desktop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RulesSheet(rules: List<WorkRule>, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Placement rules", style = MaterialTheme.typography.titleLarge)
            Text(
                "Rules put matching tasks into a group. They are made and edited on the desktop; the phone shows them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rules.isEmpty()) Text("No rules yet.", style = MaterialTheme.typography.bodyMedium)
            for (rule in rules) {
                Column {
                    Text(
                        rule.name + if (rule.enabled) "" else " (off)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("${ruleConditionsLine(rule)} → ${rule.group}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** What a rule matches, in a line. */
internal fun ruleConditionsLine(rule: WorkRule): String = with(rule.conditions) {
    buildList {
        trackerId?.let { add("tracker $it") }
        container?.takeIf { it.isNotBlank() }?.let { add("in $it") }
        keyPrefix?.takeIf { it.isNotBlank() }?.let { add("key $it-…") }
        titleContains?.takeIf { it.isNotBlank() }?.let { add("title has “$it”") }
        repo?.takeIf { it.isNotBlank() }?.let { add("repo $it") }
    }.ifEmpty { listOf("everything") }.joinToString(", ")
}
