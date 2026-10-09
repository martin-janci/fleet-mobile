package dev.claudefleet.mobile.ui

import androidx.compose.material3.Button
import dev.claudefleet.mobile.ui.components.DangerTextButton
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SessionFacetId
import dev.claudefleet.mobile.model.facetSentence
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.model.orgOf
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItemDefaults
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.semantics
import dev.claudefleet.mobile.ui.kit.Comet
import dev.claudefleet.mobile.ui.kit.MarkMotion
import dev.claudefleet.mobile.ui.kit.OrbitMarkLoader
import dev.claudefleet.mobile.ui.kit.OrbitPullToRefresh
import dev.claudefleet.mobile.ui.kit.ProgressRing
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.LocalHubReconnect
import dev.claudefleet.mobile.ui.kit.PhoneConnection
import dev.claudefleet.mobile.ui.kit.ReconnectingPanel
import dev.claudefleet.mobile.ui.kit.rememberPhoneConnection
import androidx.compose.ui.text.style.TextAlign

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
    /** Fold a host's rows away, or unfold them. The heading and its count stay. */
    val onToggleHost: (String) -> Unit = {},
    /**
     * Every filter off, the host one included — which is why this is one
     * handler and not [SessionsViewModel.clearFilters]: the host filter is the
     * navigator's (`Screen.Sessions.hostAlias`), so clearing everything means
     * calling both, and the screen must not be the place that remembers to.
     */
    val onClearAll: () -> Unit = {},
    /**
     * One chip's ✕ in the strip. The host chip goes through the navigator as
     * well, for the reason [onClearAll] does.
     */
    val onClearFacet: (SessionFacetId) -> Unit = {},
    /** The archived row at the end of the list: *Show archived* / *Hide archived*. */
    val onSetShowArchived: (Boolean) -> Unit = {},
    /**
     * Open the New session form. Null hides the button — a `readonly` pairing,
     * which the hub would refuse `new_session` anyway.
     */
    val onNewSession: (() -> Unit)? = null,
    /** Open the Tickets sheet. Null hides the action — a hub without the work graph. */
    val onOpenTickets: (() -> Unit)? = null,
    /** Open the Today sheet. Null hides the action — a hub without `work today`. */
    val onOpenToday: (() -> Unit)? = null,
    /** Open the Missions sheet. Null hides the action — a hub without missions. */
    val onOpenMissions: (() -> Unit)? = null,
    /**
     * Open the fleet's agent (the desktop's ✦). Null hides the action — a hub
     * without `ensure_operator`, or a `readonly` pairing.
     */
    val onOpenAgent: (() -> Unit)? = null,
    val onDismissAgentError: () -> Unit = {},
    /** Multi-select: pick or unpick a row, drop the selection, act on it, close its outcome. */
    val onToggleSelect: (Long) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    /** *Select* in the header's overflow: select mode with nothing picked yet. */
    val onStartSelect: () -> Unit = {},
    val onBulkSend: (String) -> Unit = {},
    val onBulkKill: () -> Unit = {},
    val onDismissBulkOutcome: () -> Unit = {},
    /** Search hits beyond sessions: a host's sessions, a new session in a project, a ticket lookup. */
    val onSearchHost: (String) -> Unit = {},
    val onSearchProject: (Long) -> Unit = {},
    val onSearchTicket: (String) -> Unit = {},
)

/**
 * The home screen: every session in the fleet, grouped by host and then by
 * project, with the triage toggles a thumb reaches for and a sheet holding the
 * rest.
 *
 * Three things ride in the header and nothing else does: **Needs you**,
 * because it is the question this screen exists to answer; **Filters (n)**,
 * which carries its own count and opens [SessionFiltersSheet]; and **Group**,
 * which is last and apart because it is not a filter at all — it regroups the
 * list without removing a row from it, and drawing it as a fourth identical
 * chip is what made it read as a filter that does nothing.
 *
 * Under them, whenever the sheet holds anything that is on, a strip: how many
 * rows are shown of how many, then one removable chip per filter in the
 * desktop's words ([SessionsUiState.stripFacets]), then *Clear all*. That
 * strip is the screen's answer to "where did my session go", and it is why
 * the other filters can safely live out of sight. Archived sessions are
 * hidden by default, and the list's last row says how many and shows them.
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
    agent: AgentUiState = AgentUiState(),
    bulk: BulkUiState = BulkUiState(),
    /** What the search finds beyond sessions; empty draws nothing. */
    hits: SearchHits = SearchHits(),
) {
    // While something is picked a tap picks too; a long press starts it.
    val select: ((Long) -> Unit)? = if (bulk.enabled) handlers.onToggleSelect else null
    val tap: (Long) -> Unit = { id -> if (bulk.active && select != null) select(id) else handlers.onOpenSession(id) }
    bulk.outcome?.let { BulkOutcomeDialog(it, handlers.onDismissBulkOutcome) }
    Column(modifier = modifier.fillMaxSize()) {
        if (bulk.active) {
            SelectionBar(bulk, handlers)
        } else SessionsBar(
            status = state.status,
            searchOpen = state.searchOpen,
            onToggleSearch = handlers.onToggleSearch,
            onOpenTickets = handlers.onOpenTickets,
            onOpenToday = handlers.onOpenToday,
            onOpenMissions = handlers.onOpenMissions,
            onStartSelect = handlers.onStartSelect.takeIf { bulk.enabled },
        ) {
            if (state.searchOpen) {
                SearchField(query = state.filters.query, onSetQuery = handlers.onSetQuery)
            }
            FilterRow(
                needsAttentionOnly = state.needsAttentionOnly,
                attentionCount = state.attentionCount,
                activeFilters = state.stripFacets.size,
                onToggleNeedsAttention = handlers.onToggleNeedsAttention,
                onOpenFilters = handlers.onOpenFilters,
                groupMode = state.groupMode,
                onCycleGroupMode = handlers.onCycleGroupMode,
            )
            // One removable chip per filter the sheet holds, and *Clear all*;
            // "3 of 87" first, so how much they hide is said where they are.
            FilterStrip(
                facets = state.stripFacets,
                onClear = handlers.onClearFacet,
                onClearAll = handlers.onClearAll,
                lead = if (state.shown != state.total) "${state.shown} of ${state.total}" else null,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        HiddenAttentionBanner(count = state.hiddenAttention, onClearAll = handlers.onClearAll)
        ConnectionBanner(state.status, staleFor = state.staleFor)
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        ErrorBanner(agent.error, onDismiss = handlers.onDismissAgentError)

        // The empty state is INSIDE the pull-to-refresh, and inside the
        // `LazyColumn` at that. It used to return early, so the one screen a
        // person would most want to pull on — no sessions yet, is the hub
        // really up? — was the one screen that did not respond to the
        // gesture. `OrbitPullToRefresh` needs a scrollable child to receive the
        // drag, which a bare `Box` is not, so the message rides as a single
        // item filling the viewport.
        OrbitPullToRefresh(
            isRefreshing = state.refreshing,
            onRefresh = handlers.onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Room under the last row for the button, or it sits on top of
            // the one session a person scrolled all the way down to reach.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = if (handlers.onOpenAgent != null) 148.dp else if (handlers.onNewSession != null) 88.dp else 0.dp),
            ) {
                // First, while searching: at the end of a long list they were
                // found only by whoever scrolled past every session — and with
                // no session matching, under an empty state filling the screen.
                if (state.searchOpen && !hits.isEmpty) searchHits(hits, handlers)
                if (state.isEmpty) {
                    item(key = "empty") {
                        EmptyFleet(
                            state = state,
                            onNewSession = handlers.onNewSession,
                            onClearAll = handlers.onClearAll,
                            onSetShowArchived = handlers.onSetShowArchived,
                            modifier = Modifier.fillParentMaxSize(),
                            onRetry = handlers.onRefresh,
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
                        orgColor = row.orgOf?.let(state.orgColors::get),
                        onClick = { tap(row.id) },
                        selected = row.id in bulk.selected,
                        onLongClick = select?.let { { it(row.id) } },
                    )
                }
                if (state.pinned.isNotEmpty()) {
                    item(key = "pinned-heading") { PinnedHeading(state.pinned.size) }
                    items(state.pinned, key = { "pinned-${it.id}" }) { row ->
                        SessionRowItem(
                            row = row,
                            nowSeconds = state.nowSeconds,
                            showWork = true,
                            showHost = true,
                            orgColor = row.orgOf?.let(state.orgColors::get),
                            onClick = { tap(row.id) },
                            selected = row.id in bulk.selected,
                            onLongClick = select?.let { { it(row.id) } },
                        )
                    }
                }
                for (host in state.groups) {
                    stickyHeader(key = "host-${host.alias}") {
                        HostHeader(
                            alias = host.alias,
                            reachable = host.reachable,
                            sessions = host.sessionCount,
                            attention = host.attentionCount,
                            collapsed = host.collapsed,
                            onClick = { handlers.onToggleHost(host.alias) },
                        )
                    }
                    // A folded host emits no items at all — which is the point:
                    // the cost of a machine you are not working on today drops
                    // from a screen of scrolling to one 40dp heading. The group
                    // still holds its rows, so unfolding is immediate and the
                    // count above is honest.
                    for (project in if (host.collapsed) emptyList() else host.projects) {
                        item(key = "${host.alias}-${project.id}") {
                            val work = project.work
                            if (work != null) WorkHeader(work, project.attentionCount, project.orgLabel) else if (project.label.isNotEmpty()) ProjectHeader(project.label)
                        }
                        items(project.sessions, key = { it.id }) { row ->
                            SessionRowItem(
                                row = row,
                                nowSeconds = state.nowSeconds,
                                // Under its work heading the key is already said.
                                showWork = project.work == null,
                                orgColor = row.orgOf?.let(state.orgColors::get),
                                onClick = { tap(row.id) },
                                selected = row.id in bulk.selected,
                                onLongClick = select?.let { { it(row.id) } },
                            )
                        }
                    }
                }
                // Archived sessions are hidden by default; the list's last
                // row says how many, and brings them all back in one tap.
                if (state.archivedRow && !state.isEmpty) {
                    item(key = "archived") {
                        ArchivedRow(
                            hidden = state.archivedHidden,
                            showing = state.filters.showArchived,
                            noun = "sessions",
                            attention = state.archivedAttention,
                            onSetShown = handlers.onSetShowArchived,
                        )
                    }
                }
            }
            if (!bulk.active && (handlers.onNewSession != null || handlers.onOpenAgent != null)) {
                Column(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // The desktop's ✦: its agent is a session on the hub, and this
                    // opens it on the Session screen. The first press may start it.
                    handlers.onOpenAgent?.let { openAgent ->
                        SmallFloatingActionButton(
                            onClick = { if (!agent.waking) openAgent() },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.semantics {
                                contentDescription = if (agent.waking) "Agent waking" else "Open the fleet agent"
                            },
                        ) {
                            if (rememberLoaderVisible(agent.waking)) Comet(size = 16.dp)
                            else Text("✦", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    handlers.onNewSession?.let { newSession ->
                        FloatingActionButton(onClick = newSession) {
                            Icon(FleetIcons.Add, contentDescription = "New session")
                        }
                    }
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
    onOpenMissions: (() -> Unit)?,
    onStartSelect: (() -> Unit)?,
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
            // The rest behind ⋮: three text buttons and an icon left the title
            // no room on a phone. The agent is a floating button over the list.
            if (onOpenTickets != null || onOpenMissions != null || onStartSelect != null) {
                var more by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { more = true }) { Icon(FleetIcons.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        if (onOpenTickets != null) {
                            DropdownMenuItem(text = { Text("Tickets") }, onClick = { more = false; onOpenTickets() })
                        }
                        if (onOpenMissions != null) {
                            DropdownMenuItem(text = { Text("Missions") }, onClick = { more = false; onOpenMissions() })
                        }
                        if (onStartSelect != null) {
                            DropdownMenuItem(text = { Text("Select sessions") }, onClick = { more = false; onStartSelect() })
                        }
                    }
                }
            }
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
 * the moment a host filter joined the "Needs you" chip the two
 * overflowed the bar and pushed the title clean off the screen. Here they own
 * the full width and *wrap* — a `FlowRow`, not a `Row`, because on a 320dp
 * screen two chips genuinely do not fit side by side and the second belongs on
 * a second line rather than half past the right edge.
 *
 * Wrapping bought room, and then the row spent it: a host token, *My work*,
 * and one chip per organisation all landed here, and at six chips the header
 * was four lines deep over the list it was filtering. So this row is now
 * capped at three by construction rather than by luck — everything that
 * narrows the list beyond *Needs you* is behind [onOpenFilters], which
 * carries the count, and everything that is on is a chip in the strip below.
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
            label = { Text("Needs you") },
            leadingIcon = {
                Icon(
                    FleetIcons.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
            trailingIcon = if (attentionCount > 0) ({ Badge { Text("$attentionCount") } }) else null,
        )
        FiltersButton(count = activeFilters, onClick = onOpenFilters)
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
 * What the filters are keeping from you, when it is something that wants a
 * person.
 *
 * This is the price of remembering filters between launches, paid in full and
 * in the open. `SessionsViewModel.setGroupMode`'s KDoc states the objection —
 * restoring a filter on launch *"can make a busy fleet look quiet"* — and this
 * is the answer to it: the fleet may look quiet, but it cannot lie about being
 * quiet, because the app says so above the list.
 *
 * Deliberately narrow. It counts only [SessionsUiState.hiddenAttention], the
 * rows that are blocked or stuck; how many rows the filters hide in general is
 * already on screen as *shown / total*. A banner that fired on every ordinary
 * narrowing would be learnt and then ignored, which is the one thing this must
 * not become.
 *
 * `errorContainer` rather than a softer tone: an agent that has stopped and is
 * waiting for an answer nobody can see is a failure of the app's whole
 * purpose, not a note about the view.
 */
@Composable
internal fun HiddenAttentionBanner(count: Int, onClearAll: () -> Unit) {
    if (count <= 0) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(FleetIcons.Warning, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = if (count == 1) {
                    "1 session is waiting on you and is hidden by these filters."
                } else {
                    "$count sessions are waiting on you and are hidden by these filters."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearAll) { Text("Clear") }
        }
    }
}

/**
 * One machine's heading, and the tap that folds it.
 *
 * The chevron is `FleetIcons.ArrowBack` rotated rather than a fourteenth hand
 * drawn path — the rotation convention is the one `SessionScreen`'s turn
 * stepper already uses: positive is clockwise, so 180° is the right-pointing
 * arrow a folded section wants and -90° is the down-pointing one an open
 * section wants.
 */
@Composable
private fun HostHeader(
    alias: String,
    reachable: Boolean?,
    sessions: Int,
    collapsed: Boolean,
    onClick: () -> Unit,
    /** Sessions under it that need a person — the one number a folded host must still say. */
    attention: Int = 0,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FleetIcons.ArrowBack,
                    contentDescription = if (collapsed) "Unfold $alias" else "Fold $alias away",
                    modifier = Modifier.size(18.dp).rotate(if (collapsed) 180f else -90f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = alias,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // A host that is not in `list_hosts` is unknown, not unreachable,
            // and says nothing rather than accusing it of being down.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (attention > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.error) { Text("$attention need you") }
                }
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
}

/** Over the pinned rows: how many sessions need a person, all hosts. */
@Composable
private fun PinnedHeading(count: Int) {
    Text(
        text = if (count == 1) "1 session needs you" else "$count sessions need you",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
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
    /** The row's org colour (ARGB), drawn as a thin bar at its start edge; null draws none. */
    orgColor: Long? = null,
    /** Picked for a bulk action: tinted, with a check for its dot. */
    selected: Boolean = false,
    /** Starts (or extends) the selection; null where this pairing cannot act on sessions. */
    onLongClick: (() -> Unit)? = null,
) {
    ListItem(
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClick?.let { "Select" })
            .then(if (orgColor != null) Modifier.orgBar(Color(orgColor)) else Modifier),
        leadingContent = {
            if (selected) Icon(FleetIcons.Check, contentDescription = "Selected", modifier = Modifier.size(16.dp))
            else StatusDot(row.claudeStatus, row.stuckKind)
        },
        headlineContent = {
            Column {
                Text(row.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val pct = row.contextPct
                if (pct != null) {
                    // The context meter as the kit's ring (manual: a known
                    // fraction is a Progress ring), small enough for the row.
                    // Padding FIRST: `Modifier` applies left to right.
                    ProgressRing(
                        fraction = (pct / 100.0).toFloat().coerceIn(0f, 1f),
                        modifier = Modifier.padding(top = 2.dp),
                        size = 14.dp,
                        color = if (pct >= 80) Fleet.colors.statusWaiting else Fleet.colors.accent,
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
                StatusChip(claudeStatus = row.claudeStatus, stuckKind = row.stuckKind, reason = row.attentionReason)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    relativeTime(row.lastActivityAt, nowSeconds)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    // A mark and a word, not a dot alone: a colour is not a status.
                    row.ciStatus?.let { ci ->
                        val (tone, mark) = when (ci) {
                            "passing" -> StatusTone.COMPLETED to "CI ✓"
                            "failing" -> StatusTone.FAILED to "CI ✗"
                            else -> StatusTone.IDLE to "CI …"
                        }
                        Text(
                            mark,
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalStatusColors.current(tone).onContainer,
                            modifier = Modifier.padding(start = 6.dp).semantics { contentDescription = "CI $ci" },
                        )
                    }
                }
            }
        },
    )
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

/** What a list that has never loaded draws while the hub is not there yet (14.12). */
internal sealed interface ColdStart {
    /** The first attempt: a quiet loader, after `loader-delay`. */
    data object Connecting : ColdStart

    /** A later attempt, still inside `hub-lost-after`: the Gravity well. */
    data object Reconnecting : ColdStart

    /** Past `hub-lost-after`, or refused: no spinner, the reason and Retry. */
    data class Unreachable(val reason: String?) : ColdStart
}

@Composable
private fun ColdStartBody(body: ColdStart, onRetry: (() -> Unit)?) {
    when (body) {
        ColdStart.Connecting -> if (rememberLoaderVisible(true)) {
            DotWave()
            Text(
                text = "Connecting to the hub…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ColdStart.Reconnecting -> ReconnectingPanel(hub = "the hub", modifier = Modifier.heightIn(min = 240.dp))
        is ColdStart.Unreachable -> {
            val reconnect = LocalHubReconnect.current
            Text(
                text = "Can't reach the hub",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body.reason ?: "The phone keeps trying. Check the network and the hub address.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { reconnect(); onRetry?.invoke() }) { Text("Retry") }
        }
    }
}

internal fun coldStartBody(connection: PhoneConnection): ColdStart = when (connection) {
    PhoneConnection.Live -> ColdStart.Connecting
    is PhoneConnection.Reconnecting -> if (connection.attempt <= 1) ColdStart.Connecting else ColdStart.Reconnecting
    is PhoneConnection.Offline -> ColdStart.Unreachable(connection.reason)
    is PhoneConnection.Refused -> ColdStart.Unreachable(connection.reason)
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
internal fun EmptyFleet(
    state: SessionsUiState,
    /** Null for a readonly pairing, which can only point elsewhere. */
    onNewSession: (() -> Unit)?,
    onClearAll: () -> Unit,
    onSetShowArchived: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    /** The screen's refresh, run with the reconnect when "Can't reach the hub" is retried. */
    onRetry: (() -> Unit)? = null,
) {
    val facets = state.facets
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            when {
                // Not "no sessions": nothing has been listed yet — what a
                // person sees opening the app from a notification. Nothing
                // for `loader-delay`, then the wait in words; past
                // `hub-lost-after` it stops spinning and says so (14.12).
                state.connecting -> ColdStartBody(coldStartBody(rememberPhoneConnection(state.status)), onRetry)
                facets.isNotEmpty() -> {
                    Text(
                        text = emptySessionsSentence(facets.let(::facetSentence)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onClearAll) { Text("Clear filters") }
                }
                // Nothing narrows, and every session there is is archived:
                // the row under this says how many and shows them.
                state.archivedHidden > 0 -> Text(
                    text = "No sessions to show.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.status is ConnectionStatus.Refused -> Text(
                    text = "This app and the hub cannot talk to each other. Update the app or the hub.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The phone can start one itself: say so, with the button.
                onNewSession != null -> {
                    Text(
                        text = "No sessions yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onNewSession) { Text("New session") }
                }
                else -> Text(
                    text = "No sessions. Start one from the desktop app or the terminal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Under an empty list the archived row belongs here, in view,
            // rather than below a message that fills the screen.
            if (state.archivedRow) {
                ArchivedRow(
                    hidden = state.archivedHidden,
                    showing = state.filters.showArchived,
                    noun = "sessions",
                    attention = state.archivedAttention,
                    onSetShown = onSetShowArchived,
                )
            }
        }
    }
}

/** The empty state's sentence, the desktop's words: "No sessions match Host: x, Last 1d." */
internal fun emptySessionsSentence(facets: String): String = "No sessions match $facets."

/** A work heading, in words: the key, its title and status, and who is waiting. */
internal fun workHeaderDescription(work: WorkSummary, attention: Int, orgLabel: String? = null): String = buildList {
    add("Work ${work.label}")
    if (work.key != null && work.title.isNotBlank()) add(work.title)
    orgLabel?.let { add("in $it") }
    work.statusName?.let { add(it) }
    if (work.unavailable) add("ticket unavailable")
    if (attention > 0) add(if (attention == 1) "1 session needs you" else "$attention sessions need you")
}.joinToString(", ")

/** An org's colour as a 3 dp bar down a row's start edge, as the desktop draws it. */
private fun Modifier.orgBar(color: Color): Modifier = drawBehind {
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - ORG_BAR_WIDTH.toPx() else 0f
    drawRect(color, topLeft = Offset(x, 0f), size = Size(ORG_BAR_WIDTH.toPx(), size.height))
}

private val ORG_BAR_WIDTH = 3.dp

/** In place of the list's bar while sessions are picked: how many, and what can be done to them. */
@Composable
private fun SelectionBar(bulk: BulkUiState, handlers: SessionsHandlers) {
    var sending by remember { mutableStateOf(false) }
    var killing by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = handlers.onClearSelection) { Icon(FleetIcons.Close, contentDescription = "Clear selection") }
            Text(if (bulk.selected.isEmpty()) "Tap sessions to pick" else "${bulk.selected.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (rememberLoaderVisible(bulk.running)) Comet(size = 16.dp)
            TextButton(onClick = { sending = true }, enabled = !bulk.running && bulk.selected.isNotEmpty()) { Text("Send") }
            DangerTextButton(onClick = { killing = true }, enabled = !bulk.running && bulk.killable > 0) { Text("Kill") }
        }
    }
    if (sending) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { sending = false },
            title = { Text("Send to ${sessionsWord(bulk.selected.size)}") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    placeholder = { Text("The same prompt, to each") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
            },
            confirmButton = {
                TextButton(onClick = { handlers.onBulkSend(text); sending = false }, enabled = text.isNotBlank()) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { sending = false }) { Text("Cancel") } },
        )
    }
    if (killing) {
        val skipped = bulk.selected.size - bulk.killable
        AlertDialog(
            onDismissRequest = { killing = false },
            title = { Text("Kill ${sessionsWord(bulk.killable)}?") },
            text = {
                Text(
                    "Each is killed now, without waiting for it to save anything." +
                        if (skipped > 0) " $skipped picked cannot be killed from here and are skipped." else "",
                )
            },
            confirmButton = { DangerTextButton(onClick = { handlers.onBulkKill(); killing = false }) { Text("Kill") } },
            dismissButton = { TextButton(onClick = { killing = false }) { Text("Cancel") } },
        )
    }
}

/** What a bulk action did, per session: what went through, and what did not and why. */
@Composable
private fun BulkOutcomeDialog(outcome: List<BulkOutcome>, onDismiss: () -> Unit) {
    val done = outcome.count { it.ok }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (done == outcome.size) "Done for all $done" else "Done for $done of ${outcome.size}") },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                for (o in outcome.filter { !it.ok }) {
                    Text(o.name, style = MaterialTheme.typography.labelLarge)
                    Text(o.reason ?: "failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (done < outcome.size) {
                    Text(
                        "The ones that did not go through stay picked.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** "1 session", "3 sessions". */
internal fun sessionsWord(n: Int): String = if (n == 1) "1 session" else "$n sessions"

/** The search's other finds, above the sessions it matched: hosts, projects, and the query as a ticket. */
private fun androidx.compose.foundation.lazy.LazyListScope.searchHits(hits: SearchHits, handlers: SessionsHandlers) {
    item(key = "search-everywhere") {
        Text(
            "Everywhere",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    if (hits.ticket) {
        item(key = "search-ticket") {
            SearchHitRow("Find ticket “${hits.query}”", "Tickets: a key, a link or words") { handlers.onSearchTicket(hits.query) }
        }
    }
    items(hits.hosts, key = { "search-host-${it.alias}" }) { host ->
        SearchHitRow(host.alias, if (host.reachable) "Host · its sessions" else "Host · unreachable") { handlers.onSearchHost(host.alias) }
    }
    if (handlers.onNewSession != null) {
        items(hits.projects, key = { "search-project-${it.id}" }) { project ->
            SearchHitRow(project.label, "New session in this project") { handlers.onSearchProject(project.id) }
        }
    }
}

@Composable
private fun SearchHitRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
