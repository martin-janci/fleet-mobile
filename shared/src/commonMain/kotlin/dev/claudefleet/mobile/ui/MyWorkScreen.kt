package dev.claudefleet.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.IdOrWord
import dev.claudefleet.mobile.model.WorkFacetId
import dev.claudefleet.mobile.model.facetSentence
import dev.claudefleet.mobile.model.WorkRule
import dev.claudefleet.mobile.model.WorkTask
import dev.claudefleet.mobile.model.WorkTreeFilters
import dev.claudefleet.mobile.model.WorkView
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.components.WorkStatusDot
import dev.claudefleet.mobile.ui.theme.FleetIcons

/** Everything the My work tab reports. */
data class MyWorkHandlers(
    val onOpenTask: (String) -> Unit = {},
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
    /** Null hides *Rules* — a hub without `work { rules }`. */
    val onOpenRules: (() -> Unit)? = null,
    val onCloseRules: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * The **My work** tab: saved views as chips, then *Filters (n)* beside the
 * *Assigned to me* and *To review* toggles, a strip of removable chips for
 * what the sheet holds, and org → group sections (headers with the hub's
 * counts) of compact task cards, each section folding away and loading more
 * of itself on its own. Archived tasks are hidden by default; the list's last
 * row says how many and shows them. The layout and the words are the
 * desktop's Work view (claude-fleet `WorkFiltersBar.svelte`). When the hub
 * cannot be reached the last page stays, under "Offline · as of 10:42".
 *
 * Stateless: it draws a [MyWorkUiState]; [MyWorkViewModel] is what is tested.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MyWorkScreen(
    state: MyWorkUiState,
    handlers: MyWorkHandlers = MyWorkHandlers(),
    reviewCount: Int = state.reviewCount,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "My work",
            subtitle = state.stale ?: if (state.loaded) "${state.total} task${if (state.total == 1) "" else "s"}" else null,
            actions = {
                IconButton(onClick = handlers.onToggleSearch) {
                    Icon(FleetIcons.Search, contentDescription = if (state.searchOpen) "Close search" else "Search tasks")
                }
                handlers.onOpenReview?.let { openReview ->
                    TextButton(onClick = openReview) {
                        if (reviewCount > 0) {
                            BadgedBox(badge = { Badge { Text("$reviewCount") } }) { Text("Review") }
                        } else {
                            Text("Review")
                        }
                    }
                }
            },
            below = {
                if (state.searchOpen) {
                    OutlinedTextField(
                        value = state.filters.query.orEmpty(),
                        onValueChange = handlers.onSetQuery,
                        label = { Text("Key or title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (state.viewsAvailable && state.views.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    ) {
                        items(state.views, key = { it.id }) { view ->
                            FilterChip(
                                selected = view.id == state.activeViewId,
                                onClick = { handlers.onApplyView(view) },
                                label = { Text(view.name, maxLines = 1) },
                            )
                        }
                    }
                }
                // The sheet's entry, then the two quick toggles, which show
                // their own state and so get no chip in the strip.
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FiltersButton(count = state.filters.sheetCount, onClick = handlers.onOpenFilters)
                    FilterChip(
                        selected = state.filters.mine == true,
                        onClick = handlers.onToggleMine,
                        label = { Text("Assigned to me") },
                    )
                    FilterChip(
                        selected = state.filters.review == true,
                        onClick = handlers.onToggleReview,
                        label = { Text("To review") },
                    )
                }
                FilterStrip(
                    facets = state.stripFacets,
                    onClear = handlers.onClearFacet,
                    onClearAll = handlers.onClearFilters,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            },
        )
        state.stale?.let { StaleNotice(it) }
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        if (state.error != null && state.conflict) ReloadRow(handlers.onReload)
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = handlers.onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.isEmpty) {
                    item(key = "empty") {
                        EmptyWork(state, handlers, Modifier.fillParentMaxSize())
                    }
                } else if (!state.loaded && !state.loading) {
                    item(key = "not-yet") {
                        Text(
                            if (state.connected) "Nothing read yet — pull to load." else "Not connected to the hub.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                for (org in state.orgs) {
                    stickyHeader(key = org.key) {
                        OrgHeader(org, onClick = { handlers.onToggleSection(org.key) })
                    }
                    if (org.collapsed) continue
                    for (group in org.groups) {
                        item(key = "g-${group.key}") {
                            GroupHeader(group, onClick = { handlers.onToggleSection(group.key) })
                        }
                        if (group.collapsed) continue
                        items(group.tasks, key = { "t-${group.key}-${it.taskId}" }) { task ->
                            TaskCard(task, onClick = { handlers.onOpenTask(task.taskId) })
                        }
                        if (group.hasMore || group.error != null || group.loadingMore) {
                            item(key = "more-${group.key}") {
                                LoadMoreRow(group, state.connected, onClick = { handlers.onLoadMore(group.key) })
                            }
                        }
                    }
                }
                // Archived tasks are hidden by default; the last row says how
                // many (the hub's `archived_hidden`) and shows them all.
                if (state.archivedRow && !state.isEmpty) {
                    item(key = "archived") {
                        ArchivedRow(state.archivedHidden, state.showArchived, "tasks", handlers.onSetArchived)
                    }
                }
            }
        }
    }
    if (state.filtersOpen) WorkFiltersSheet(state, handlers)
    if (state.rulesOpen) RulesSheet(state.rules, handlers.onCloseRules)
}

@Composable
internal fun StaleNotice(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** *Reload* beside a conflict: what the hub has now, instead of what this screen last read. */
@Composable
internal fun ReloadRow(onReload: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = onReload) { Text("Reload") }
    }
}

@Composable
private fun OrgHeader(org: WorkOrgSection, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chevron(org.collapsed, org.name)
                Spacer(Modifier.width(4.dp))
                Text(org.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text("${org.count}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun GroupHeader(group: WorkGroupSection, onClick: () -> Unit) {
    val words = groupSourceWords(group.group)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
            .clearAndSetSemantics { contentDescription = "${group.group.title}, ${group.count} tasks, $words" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(group.collapsed, group.group.title)
        Spacer(Modifier.width(4.dp))
        Text(
            group.group.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("${group.count}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Chevron(collapsed: Boolean, name: String) {
    Icon(
        FleetIcons.ArrowBack,
        contentDescription = if (collapsed) "Unfold $name" else "Fold $name away",
        modifier = Modifier.size(18.dp).rotate(if (collapsed) 180f else -90f),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A compact task card: key, title, status, tracker, counts, and what wants a person. */
@Composable
internal fun TaskCard(task: WorkTask, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { task.statusCategory?.let { WorkStatusDot(it) } },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.label,
                    style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (task.unavailable) TextDecoration.LineThrough else null,
                )
                if (task.review) {
                    Text(" ?", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (task.title.isNotBlank() && task.key != null) {
                    Text(
                        " · ${task.title}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        supportingContent = {
            val line = taskCardLine(task)
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            if (task.needsYou) Text("needs you", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        },
    )
}

/** The card's second line, in words — out here so it can be asserted. */
internal fun taskCardLine(task: WorkTask): String = buildList {
    task.statusName?.takeIf { it.isNotBlank() }?.let { add(it) }
    task.trackerName?.takeIf { it.isNotBlank() }?.let { add(it) }
    val c = task.counts
    if (c.active > 0) add("${c.active} active")
    if (c.ended > 0) add("${c.ended} past")
    if (c.suggested > 0) add("${c.suggested} suggested")
    if (c.active == 0 && c.ended == 0 && c.suggested == 0) add("no sessions")
    if (task.unavailable) add("unavailable")
    if (task.trackerDown) add("tracker down")
    if (task.review) add("to review")
}.joinToString(" · ")

@Composable
private fun LoadMoreRow(group: WorkGroupSection, connected: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        group.error?.let { Text(it.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (group.loadingMore) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (group.hasMore) {
            TextButton(onClick = onClick, enabled = connected) {
                Text("Load more (${group.tasks.size} of ${group.count})")
            }
        }
    }
}

@Composable
private fun EmptyWork(state: MyWorkUiState, handlers: MyWorkHandlers, modifier: Modifier) {
    Column(modifier = modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            // The desktop's words: what is on, and the way out under it.
            state.facets.isNotEmpty() -> {
                Text(emptyWorkSentence(facetSentence(state.facets)), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = handlers.onClearFilters) { Text("Clear filters") }
            }
            state.archivedHidden > 0 -> Text("No tasks to show.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text("No tasks here", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tasks appear when a tracker is connected on the hub, or a session is linked to work.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (state.archivedRow) ArchivedRow(state.archivedHidden, state.showArchived, "tasks", handlers.onSetArchived)
    }
}

/** "No tasks match Org: Acme, Status: Done." — the desktop's empty state. */
internal fun emptyWorkSentence(facets: String): String = "No tasks match $facets."

/**
 * One sheet of labelled chip groups — Organisation, Tracker, Status,
 * Sessions, each led by *Any* — then *Show archived*, and saving the
 * filters as a view. *Assigned to me* and *To review* are on the screen.
 *
 * A remembered org or tracker the last page no longer lists still gets its
 * chip, selected, so a filter that is on always has a control that shows it
 * and turns it off (the Organisation group used to vanish when the page
 * listed no orgs, leaving `org: none` narrowing the tree unseen).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WorkFiltersSheet(state: MyWorkUiState, handlers: MyWorkHandlers) {
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
                if (active != null && state.canDeleteView) TextButton(onClick = { handlers.onDeleteView(active) }) { Text("Delete “${active.name}”") }
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
private fun RulesSheet(rules: List<WorkRule>, onClose: () -> Unit) {
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
