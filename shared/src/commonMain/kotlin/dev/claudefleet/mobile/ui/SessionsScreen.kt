package dev.claudefleet.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.components.StatusChip
import dev.claudefleet.mobile.ui.components.StatusDot
import dev.claudefleet.mobile.ui.components.WorkChip
import dev.claudefleet.mobile.ui.components.WorkStatusDot
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.LocalStatusColors
import dev.claudefleet.mobile.ui.theme.StatusTone

/**
 * Everything the fleet list reports.
 *
 * A data class rather than eighteen parameters, the way [TodayHandlers] and
 * [SessionFiltersHandlers] already are: the screen grew from three controls to
 * a sheet's worth, and a call site of eighteen positional lambdas is one
 * transposition away from wiring *clear all* to *refresh*.
 */
data class SessionsHandlers(
    val onOpenSession: (Long) -> Unit = {},
    val onToggleNeedsAttention: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** Move to the next view: project → work → urgency. Work is skipped without the work graph. */
    val onCycleGroupMode: () -> Unit = {},
    /** Show the search field, or hide it (hiding clears the query). */
    val onToggleSearch: () -> Unit = {},
    val onSetQuery: (String) -> Unit = {},
    val onOpenFilters: () -> Unit = {},
    /**
     * Every filter off, the host one included — which is why this is one
     * handler and not [SessionsViewModel.clearFilters]: the host filter is the
     * navigator's (`Screen.Sessions.hostAlias`), so clearing everything means
     * calling both, and the screen must not be the place that remembers to.
     */
    val onClearAll: () -> Unit = {},
    /**
     * Open the New session form. Null hides the button — a `readonly` pairing,
     * which the hub would refuse `new_session` anyway.
     */
    val onNewSession: (() -> Unit)? = null,
    /** Open the Tickets sheet. Null hides the action — a hub without the work graph. */
    val onOpenTickets: (() -> Unit)? = null,
    /** Open the Today sheet. Null hides the action — a hub without `work today`. */
    val onOpenToday: (() -> Unit)? = null,
)

/**
 * The home screen: every session in the fleet, grouped by host and then by
 * project, with the triage toggles a thumb reaches for and a sheet holding the
 * rest.
 *
 * Three things ride in the header and nothing else does: **Needs attention**,
 * because it is the question this screen exists to answer; **Filters**, which
 * carries its own count and opens [SessionFiltersSheet]; and **By work**,
 * which is last and apart because it is not a filter at all — it regroups the
 * list without removing a row from it, and drawing it as a fourth identical
 * chip is what made it read as a filter that does nothing.
 *
 * Under them, whenever anything is on, one line saying how many rows are
 * hidden and by what. That line is the screen's answer to "where did my
 * session go", and it is why the other filters can safely live out of sight.
 *
 * Stateless by design — it draws a [SessionsUiState] and reports taps. The view
 * model is what is tested; this is what only a device can show.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    state: SessionsUiState,
    handlers: SessionsHandlers = SessionsHandlers(),
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SessionsBar(
            status = state.status,
            searchOpen = state.searchOpen,
            onToggleSearch = handlers.onToggleSearch,
            onOpenTickets = handlers.onOpenTickets,
            onOpenToday = handlers.onOpenToday,
        ) {
            if (state.searchOpen) {
                SearchField(query = state.filters.query, onSetQuery = handlers.onSetQuery)
            }
            FilterRow(
                needsAttentionOnly = state.needsAttentionOnly,
                attentionCount = state.attentionCount,
                activeFilters = state.filters.sheetCount,
                onToggleNeedsAttention = handlers.onToggleNeedsAttention,
                onOpenFilters = handlers.onOpenFilters,
                groupMode = state.groupMode,
                onCycleGroupMode = handlers.onCycleGroupMode,
            )
            FilterSummary(state = state, onOpenFilters = handlers.onOpenFilters, onClearAll = handlers.onClearAll)
        }
        ConnectionBanner(state.status)
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)

        // The empty state is INSIDE the pull-to-refresh, and inside the
        // `LazyColumn` at that. It used to return early, so the one screen a
        // person would most want to pull on — no sessions yet, is the hub
        // really up? — was the one screen that did not respond to the
        // gesture. `PullToRefreshBox` needs a scrollable child to receive the
        // drag, which a bare `Box` is not, so the message rides as a single
        // item filling the viewport.
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = handlers.onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Room under the last row for the button, or it sits on top of
            // the one session a person scrolled all the way down to reach.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = if (handlers.onNewSession != null) 88.dp else 0.dp),
            ) {
                if (state.isEmpty) {
                    item(key = "empty") {
                        EmptyFleet(
                            state = state,
                            onClearAll = handlers.onClearAll,
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                }
                // The ranked queue has no headings to group under: one flat
                // list, worst first. Each row therefore carries its own host,
                // which the tree leaves to the sticky header.
                items(state.urgent, key = { "urgent-${it.id}" }) { row ->
                    SessionRowItem(
                        row = row,
                        nowSeconds = state.nowSeconds,
                        showWork = true,
                        showHost = true,
                        onClick = { handlers.onOpenSession(row.id) },
                    )
                }
                for (host in state.groups) {
                    stickyHeader(key = "host-${host.alias}") {
                        HostHeader(alias = host.alias, reachable = host.reachable, sessions = host.sessionCount)
                    }
                    for (project in host.projects) {
                        item(key = "${host.alias}-${project.id}") {
                            val work = project.work
                            if (work != null) WorkHeader(work, project.attentionCount, project.orgLabel) else ProjectHeader(project.label)
                        }
                        items(project.sessions, key = { it.id }) { row ->
                            SessionRowItem(
                                row = row,
                                nowSeconds = state.nowSeconds,
                                // Under its work heading the key is already said.
                                showWork = project.work == null,
                                onClick = { handlers.onOpenSession(row.id) },
                            )
                        }
                    }
                }
            }
            handlers.onNewSession?.let { newSession ->
                FloatingActionButton(
                    onClick = newSession,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Icon(FleetIcons.Add, contentDescription = "New session")
                }
            }
        }
    }
}

/** The Sessions header: the title, whether the list is live, and [filters] under both. */
@Composable
private fun SessionsBar(
    status: ConnectionStatus,
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    onOpenTickets: (() -> Unit)?,
    onOpenToday: (() -> Unit)?,
    filters: @Composable () -> Unit,
) {
    val live = when (status) {
        is ConnectionStatus.Connected -> "live"
        is ConnectionStatus.Reconnecting -> "reconnecting…"
        is ConnectionStatus.Offline -> "offline"
        // Not "offline": the hub is up and answering. The banner under this
        // header says which side is behind.
        is ConnectionStatus.Refused -> "refused"
    }
    ScreenHeader(
        title = "Sessions",
        subtitle = live,
        // A sheet, not a fourth tab: the phone is a pager.
        actions = {
            // An icon rather than a permanent field: search is the fastest
            // filter there is on a long list, but a text field is 48 dp of
            // chrome above every session, every launch, for a control most
            // openings of this screen never touch.
            IconToggle(
                on = searchOpen,
                icon = FleetIcons.Search,
                label = if (searchOpen) "Hide search" else "Search sessions",
                onClick = onToggleSearch,
            )
            if (onOpenToday != null) TextButton(onClick = onOpenToday) { Text("Today") }
            if (onOpenTickets != null) TextButton(onClick = onOpenTickets) { Text("Tickets") }
        },
        below = { filters() },
    )
}

/** An icon button that shows whether the thing it opens is open. */
@Composable
private fun IconToggle(on: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (on) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

/**
 * The search field, shown only while it is open.
 *
 * `singleLine`, because a wrapped search box pushes the list down as someone
 * types, and the query is one phrase. The trailing ✕ clears the text without
 * closing the field — closing it is the icon in the header, and a person
 * mid-search usually wants the next query rather than no query.
 *
 * The IME key only drops focus. It was wired to the header's search toggle,
 * which *clears the query on the way out* — so pressing the button marked
 * **Search** threw the search away. The list is already filtered by then;
 * there is nothing for the key to submit, and the only useful thing it can do
 * is put the keyboard away and show more of the result.
 */
@Composable
private fun SearchField(query: String, onSetQuery: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onSetQuery,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        placeholder = { Text("Search sessions, branches, projects…") },
        singleLine = true,
        leadingIcon = { Icon(FleetIcons.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onSetQuery("") }) {
                    Icon(FleetIcons.Close, contentDescription = "Clear search")
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

/**
 * The three controls that stay on screen, on their own line under the title.
 *
 * They used to ride in the `TopAppBar`'s `actions`, which is a plain `Row`
 * that neither wraps nor scrolls and is measured before the title: on a phone,
 * the moment a host filter joined the "Needs attention" chip the two
 * overflowed the bar and pushed the title clean off the screen. Here they own
 * the full width and *wrap* — a `FlowRow`, not a `Row`, because on a 320dp
 * screen two chips genuinely do not fit side by side and the second belongs on
 * a second line rather than half past the right edge.
 *
 * Wrapping bought room, and then the row spent it: a host token, *My work*,
 * and one chip per organisation all landed here, and at six chips the header
 * was four lines deep over the list it was filtering. So this row is now
 * capped at three by construction rather than by luck — everything that
 * narrows the list beyond *Needs attention* is behind [onOpenFilters], which
 * carries the count, and everything that is on is named on the line below.
 *
 * [byWork] sits last and is not a filter: it regroups the list without
 * removing a row, which is why it is outside the count. It keeps a chip of its
 * own because it is a *view*, and a view control belongs where its effect is
 * visible.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(
    needsAttentionOnly: Boolean,
    attentionCount: Int,
    activeFilters: Int,
    onToggleNeedsAttention: () -> Unit,
    onOpenFilters: () -> Unit,
    groupMode: GroupMode = GroupMode.PROJECT,
    onCycleGroupMode: () -> Unit = {},
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = needsAttentionOnly,
            onClick = onToggleNeedsAttention,
            label = { Text("Needs attention") },
            leadingIcon = {
                Icon(
                    FleetIcons.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
            trailingIcon = if (attentionCount > 0) ({ Badge { Text("$attentionCount") } }) else null,
        )
        FilterChip(
            selected = activeFilters > 0,
            onClick = onOpenFilters,
            label = { Text(if (activeFilters > 0) "Filters · $activeFilters" else "Filters") },
            leadingIcon = {
                Icon(
                    FleetIcons.Filters,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )
        // The label names the view you are *in*, not the one a tap would give
        // — the desktop's `group-by-toggle`. A cycling control whose label
        // promises the next state leaves you unable to read the current one.
        FilterChip(
            selected = groupMode != GroupMode.PROJECT,
            onClick = onCycleGroupMode,
            label = { Text("Group: ${groupMode.label}") },
        )
    }
}

/**
 * One line saying how many sessions are hidden and by what, drawn only while
 * something is on.
 *
 * The cheapest thing on this screen and the one that makes the rest safe. With
 * the filters behind a sheet a person can leave one on, close the app, come
 * back tomorrow and read a three-session list as a quiet fleet — so the list
 * says "3 of 87" in its own chrome, names what is doing it, and offers the way
 * out. Tapping the line reopens the sheet; the ✕ clears everything.
 *
 * The counts come from the view model rather than from `groups.sumOf` here,
 * because a session hidden by a filter and a session on a host that is simply
 * absent look identical once the tree is built.
 */
@Composable
private fun FilterSummary(state: SessionsUiState, onOpenFilters: () -> Unit, onClearAll: () -> Unit) {
    if (!state.filters.any) return
    val names = state.filters.summary { id -> state.orgChoices.firstOrNull { it.id == id }?.name ?: "org #$id" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenFilters)
            .padding(start = 16.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${state.shown} of ${state.total} · ${names.joinToString(", ")}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClearAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Clear", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun HostHeader(alias: String, reachable: Boolean?, sessions: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = alias,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // A host that is not in `list_hosts` is unknown, not unreachable,
            // and says nothing rather than accusing it of being down.
            Text(
                text = when (reachable) {
                    true -> "$sessions session${if (sessions == 1) "" else "s"}"
                    false -> "unreachable · $sessions"
                    null -> "$sessions"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ProjectHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * A work group's heading: key, status, title, and how many of its sessions
 * want a person. The title is the tracker's text, drawn plain.
 */
@Composable
private fun WorkHeader(work: WorkSummary, attention: Int, orgLabel: String? = null) {
    val spoken = workHeaderDescription(work, attention, orgLabel)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            // Read as one heading, in words: the strike-through and the dot
            // say nothing to a screen reader on their own.
            .clearAndSetSemantics {
                heading()
                contentDescription = spoken
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        work.statusCategory?.let { WorkStatusDot(it) }
        Text(
            text = if (work.key != null && work.title.isNotBlank()) "${work.label} · ${work.title}" else work.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            textDecoration = if (work.unavailable) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (orgLabel != null) {
            Text(
                orgLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).widthIn(max = 120.dp),
            )
        }
        if (attention > 0) Badge(modifier = Modifier.padding(start = 8.dp)) { Text("$attention") }
    }
}

@Composable
private fun SessionRowItem(
    row: SessionRow,
    nowSeconds: Long,
    showWork: Boolean,
    onClick: () -> Unit,
    /** Name the host on the row itself — for the urgency queue, which has no host heading. */
    showHost: Boolean = false,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { StatusDot(row.claudeStatus, row.stuckKind) },
        headlineContent = {
            Column {
                Text(row.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val pct = row.contextPct
                if (pct != null) {
                    LinearProgressIndicator(
                        progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) },
                        // Padding FIRST. `Modifier` applies left to right, so
                        // `.height(2.dp).padding(top = 2.dp)` sized the bar to
                        // 2 dp and then spent both of them on padding: a
                        // context meter that measured to zero and drew
                        // nothing. Padding first pads a 2 dp bar instead.
                        modifier = Modifier.padding(top = 2.dp).width(60.dp).height(2.dp),
                        color = if (pct >= 80) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            val line = listOfNotNull(row.hostAlias.takeIf { showHost && it.isNotBlank() }, row.supportingLine)
                .joinToString(" · ")
                .takeIf { it.isNotEmpty() }
            val work = row.work?.takeIf { showWork }
            val guess = row.workSuggested
            if (work != null || guess != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    work?.let { WorkChip(it, suggested = false) }
                    guess?.let { WorkChip(it, suggested = true) }
                    if (line != null) Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (line != null) {
                Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                StatusChip(claudeStatus = row.claudeStatus, stuckKind = row.stuckKind)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    relativeTime(row.lastActivityAt, nowSeconds)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    row.ciStatus?.let { ci ->
                        val tone = when (ci) { "passing" -> StatusTone.COMPLETED; "failing" -> StatusTone.FAILED; else -> StatusTone.IDLE }
                        Box(Modifier.padding(start = 4.dp).size(6.dp).clip(CircleShape).background(LocalStatusColors.current(tone).dot))
                    }
                }
            }
        },
    )
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

/**
 * What the list says when it has nothing to draw.
 *
 * It used to be a `when` over three filters, and it already lied: *My work*
 * won the branch whenever it was on, so "No session is on your tickets" was
 * printed over a list that a host filter was also emptying, and clearing the
 * one the message named left the screen just as blank. Four filters would have
 * made that `when` sixteen branches and every added filter doubles it — a
 * message that is wrong in a way a person cannot act on.
 *
 * So it says one true thing instead, names every filter that is on, and puts
 * the way out under it. The only special case left is the one that is not
 * about filters at all: an empty fleet, where the useful sentence is how to
 * start a session rather than what to clear.
 */
@Composable
private fun EmptyFleet(state: SessionsUiState, onClearAll: () -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    val names = state.filters.summary { id -> state.orgChoices.firstOrNull { it.id == id }?.name ?: "org #$id" }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            if (names.isEmpty()) {
                Text(
                    text = "No sessions. Start one from the desktop app or the terminal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = if (state.total == 1) {
                        "The one session in the fleet does not match your filters"
                    } else {
                        "None of the ${state.total} sessions match your filters"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = names.joinToString(", "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onClearAll) { Text("Clear all filters") }
            }
        }
    }
}

/** A work heading, in words: the key, its title and status, and who is waiting. */
internal fun workHeaderDescription(work: WorkSummary, attention: Int, orgLabel: String? = null): String = buildList {
    add("Work ${work.label}")
    if (work.key != null && work.title.isNotBlank()) add(work.title)
    orgLabel?.let { add("in $it") }
    work.statusName?.let { add(it) }
    if (work.unavailable) add("ticket unavailable")
    if (attention > 0) add(if (attention == 1) "1 session needs you" else "$attention sessions need you")
}.joinToString(", ")
