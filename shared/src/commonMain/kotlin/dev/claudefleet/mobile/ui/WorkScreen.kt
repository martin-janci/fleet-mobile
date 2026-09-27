package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.OrgChoice
import dev.claudefleet.mobile.model.TaskHasChoice
import dev.claudefleet.mobile.model.TaskLink
import dev.claudefleet.mobile.model.TaskStatusChoice
import dev.claudefleet.mobile.model.TrackerChoice
import dev.claudefleet.mobile.model.WorkTask
import dev.claudefleet.mobile.model.WorkView
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.components.StatusDot
import dev.claudefleet.mobile.ui.components.WorkStatusDot
import dev.claudefleet.mobile.ui.theme.FleetIcons

/** Everything the *My work* tab reports. */
data class WorkTreeHandlers(
    val onPull: () -> Unit = {},
    val onOpenTask: (String) -> Unit = {},
    val onApplyView: (WorkView) -> Unit = {},
    val onOpenFilters: () -> Unit = {},
    val onToggleOrg: (Long?) -> Unit = {},
    val onToggleSection: (WorkSectionKey) -> Unit = {},
    val onLoadMore: (WorkSectionKey) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * The *My work* tab: saved views as chips, then org → group sections of
 * compact task cards, each section with its own *Load more*. Read-only:
 * nothing here changes the work graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkScreen(state: WorkTreeUiState, handlers: WorkTreeHandlers, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "My work",
            subtitle = if (state.loaded) "${state.total} task${if (state.total == 1) "" else "s"}" else null,
            actions = {
                IconButton(onClick = handlers.onOpenFilters) {
                    val n = state.filters.active
                    if (n > 0) {
                        BadgedBox(badge = { Badge { Text("$n") } }) { Icon(FleetIcons.Filters, contentDescription = "Filters, $n on") }
                    } else {
                        Icon(FleetIcons.Filters, contentDescription = "Filters")
                    }
                }
            },
            below = if (state.views.isEmpty()) null else ({ ViewChips(state, handlers.onApplyView) }),
        )
        state.stale?.let { StaleBanner(it) }
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)

        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = handlers.onPull, modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (!state.loaded) {
                    item(key = "loading") {
                        Message(if (state.loading) "Loading…" else "Pull to load your work.")
                    }
                } else if (state.isEmpty) {
                    item(key = "empty") {
                        Message(if (state.filters.active > 0) "No task matches these filters." else "No work yet.")
                    }
                }
                for (org in state.orgs) {
                    item(key = "org:${org.orgId}") {
                        OrgHeader(org, onClick = { handlers.onToggleOrg(org.orgId) })
                    }
                    if (org.collapsed) continue
                    for (section in org.sections) {
                        item(key = "section:${section.key.orgId}:${section.key.groupId}") {
                            SectionHeader(section, onClick = { handlers.onToggleSection(section.key) })
                        }
                        if (section.collapsed) continue
                        items(section.tasks, key = { "task:${section.key.orgId}:${section.key.groupId}:${it.taskId}" }) { task ->
                            TaskCard(task, onClick = { handlers.onOpenTask(task.taskId) })
                        }
                        if (section.canLoadMore) {
                            item(key = "more:${section.key.orgId}:${section.key.groupId}") {
                                TextButton(
                                    onClick = { handlers.onLoadMore(section.key) },
                                    enabled = !section.loadingMore,
                                    modifier = Modifier.padding(start = 24.dp),
                                ) {
                                    Text(if (section.loadingMore) "Loading…" else "Load more (${(section.count - section.tasks.size).coerceAtLeast(0)})")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewChips(state: WorkTreeUiState, onApply: (WorkView) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (view in state.views) {
            FilterChip(
                selected = view.id == state.activeViewId,
                onClick = { onApply(view) },
                label = { Text(view.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** "Offline · as of 10:42": the picture on screen is the last one read, not a live one. */
@Composable
private fun StaleBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(32.dp),
    )
}

@Composable
private fun OrgHeader(org: WorkOrgSection, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClickLabel = if (org.collapsed) "Show ${org.name}" else "Hide ${org.name}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (org.collapsed) "▸" else "▾", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Text(org.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${org.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SectionHeader(section: WorkSection, onClick: () -> Unit) {
    val label = section.group.label.ifBlank { "No group" }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClickLabel = if (section.collapsed) "Show $label" else "Hide $label", onClick = onClick)
            .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (section.collapsed) "▸" else "▾", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("${section.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One task, compact: its label and status, its counts, and its first few sessions. */
@Composable
private fun TaskCard(task: WorkTask, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClickLabel = "Open ${task.label}", onClick = onClick)
            .padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            task.statusCategory?.let { WorkStatusDot(it) }
            Text(
                text = task.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (task.unavailable) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
            )
            if (task.needsYou) Badge { Text("needs you") }
            if (task.review) {
                Spacer(Modifier.width(4.dp))
                Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) { Text("review") }
            }
        }
        Text(
            text = taskSummaryLine(task),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        for (link in task.sessions) SessionLine(link)
    }
}

@Composable
private fun SessionLine(link: TaskLink) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        if (link.state == LinkState.ACTIVE) StatusDot(link.claudeStatus, stuckKind = null)
        Spacer(Modifier.width(4.dp))
        Text(
            text = linkLine(link),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `In Review · 1 active · 2 past · 1 suggested` — what the card says under its title. */
internal fun taskSummaryLine(task: WorkTask): String = buildList {
    task.statusName?.let { add(it) }
    if (task.unavailable) add("unavailable")
    val c = task.counts
    if (c.active > 0) add("${c.active} active")
    if (c.ended > 0) add("${c.ended} past")
    if (c.suggested > 0) add("${c.suggested} suggested")
    if (c.active + c.ended + c.suggested == 0) add("no session")
}.joinToString(" · ")

/** `pine · api · primary`, or `pine · api · ended` — one session under a task. */
internal fun linkLine(link: TaskLink): String = buildList {
    link.host?.let { add(it) }
    add(link.name.ifBlank { "session" })
    add(
        when (link.state) {
            LinkState.ACTIVE -> if (link.primary) "primary" else "secondary"
            LinkState.SUGGESTED -> "suggested"
            LinkState.ENDED -> "ended"
            LinkState.REJECTED -> "not this"
            else -> link.state
        },
    )
    if (link.needsYou) add("needs you")
}.joinToString(" · ")

/** Everything the filter sheet reports. */
data class WorkFiltersHandlers(
    val onClose: () -> Unit = {},
    val onSetOrg: (OrgChoice?) -> Unit = {},
    val onSetTracker: (TrackerChoice?) -> Unit = {},
    val onSetStatus: (TaskStatusChoice) -> Unit = {},
    val onSetHas: (TaskHasChoice) -> Unit = {},
    val onToggleMine: () -> Unit = {},
    val onToggleReview: () -> Unit = {},
    val onSetQuery: (String) -> Unit = {},
    val onClearAll: () -> Unit = {},
)

/**
 * The desktop's filters in a sheet: org, tracker, status, mine, has,
 * review, search. The org and tracker choices are the ones the hub listed
 * for this token — a phone bound to one org sees that org and nothing else.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkFiltersSheet(state: WorkTreeUiState, handlers: WorkFiltersHandlers) {
    val f = state.filters
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = f.query,
                onValueChange = handlers.onSetQuery,
                label = { Text("Search key or title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.orgChoices.isNotEmpty() || state.offerUnassigned) {
                Heading("Organisation")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("All", f.org == null) { handlers.onSetOrg(null) }
                    for (org in state.orgChoices) {
                        val choice = OrgChoice.Org(org.id)
                        Chip(org.name, f.org == choice) { handlers.onSetOrg(choice) }
                    }
                    if (state.offerUnassigned) {
                        Chip("Unassigned", f.org == OrgChoice.Unassigned) { handlers.onSetOrg(OrgChoice.Unassigned) }
                    }
                }
            }
            Heading("Tracker")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All", f.tracker == null) { handlers.onSetTracker(null) }
                for (tracker in state.trackerChoices) {
                    val choice = TrackerChoice.Tracker(tracker.id)
                    Chip(tracker.name, f.tracker == choice) { handlers.onSetTracker(choice) }
                }
                Chip("Local", f.tracker == TrackerChoice.Local) { handlers.onSetTracker(TrackerChoice.Local) }
                Chip("Bare keys", f.tracker == TrackerChoice.Ref) { handlers.onSetTracker(TrackerChoice.Ref) }
            }
            Heading("Status")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in TaskStatusChoice.entries) Chip(s.label, f.status == s) { handlers.onSetStatus(s) }
            }
            Heading("Sessions")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in TaskHasChoice.entries) Chip(h.label, f.has == h) { handlers.onSetHas(h) }
            }
            SwitchRow("Assigned to me", f.mine, handlers.onToggleMine)
            SwitchRow("Something to review", f.review, handlers.onToggleReview)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = handlers.onClearAll, enabled = f.active > 0) { Text("Clear all") }
                TextButton(onClick = handlers.onClose) { Text("Show ${state.total}") }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box { Switch(checked = checked, onCheckedChange = { onToggle() }) }
    }
}
