package dev.claudefleet.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SessionFacetId
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.AccountLimit
import dev.claudefleet.mobile.model.limitAt
import dev.claudefleet.mobile.model.relativeWithin
import dev.claudefleet.mobile.model.reasonLabel
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.HubBanner
import dev.claudefleet.mobile.ui.kit.OrbitChip
import dev.claudefleet.mobile.ui.kit.OrbitPullToRefresh
import dev.claudefleet.mobile.ui.kit.PhoneRow
import dev.claudefleet.mobile.ui.kit.SheetAction
import dev.claudefleet.mobile.ui.kit.StatusWord
import dev.claudefleet.mobile.ui.kit.rememberPhoneConnection
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import dev.claudefleet.mobile.ui.theme.StatusTone

// ── Words a phone row says (PhoneRow, MobileNav) ──

/**
 * The status word a session row leads with. A row that needs a person reads
 * Needs you, or Failed when what it needs is fixing (stuck, failed); every
 * other row reads its tone's word.
 */
internal fun phoneWord(row: SessionRow): StatusWord? =
    if (row.needsAttention) inboxWord(row) else StatusWord.of(StatusTone.of(row.claudeStatus, row.stuckKind))

/**
 * How line two opens: the manual's "Waiting for you:" for Needs you, and
 * "Was working" for a working row the phone can no longer see live (PhoneRow:
 * "show stale data with its age and *Was working*, with no spinner").
 */
internal fun phoneLead(word: StatusWord?, live: Boolean): String? = when {
    word == StatusWord.NEEDS_YOU -> "Waiting for you"
    word == StatusWord.WORKING && !live -> "Was working"
    else -> word?.label
}

/**
 * What a row waits on, or is doing: the question it asked when it asked one,
 * else its activity, else the reason in words. Never the host — a grouped
 * list says that in the heading, a flat one in a chip.
 */
internal fun waitsOn(
    row: SessionRow,
    accountName: String? = null,
    limit: AccountLimit? = null,
    nowSeconds: Long = 0,
): String =
    row.pendingInput?.question?.trim()?.takeIf { it.isNotEmpty() }?.takeIf { row.needsAttention }
        ?: blockedLine(row, accountName, limit, nowSeconds)
        ?: row.supportingLine
        ?: row.attentionReason?.let(::reasonLabel)
        ?: ""

/**
 * A Blocked row's line (redesign step 4.10), as the desktop words it:
 * "Paused · limit on tech.silvester", "tech.silvester is signed out",
 * "Host down". Null for any other row. With the account's usage reading
 * ([limit], from `account_usage`) a paused row also says which window and
 * when it resets: "Paused · weekly limit on tech.silvester · resets in 3 d".
 */
internal fun blockedLine(
    row: SessionRow,
    accountName: String?,
    limit: AccountLimit? = null,
    nowSeconds: Long = 0,
): String? = when (row.attention?.reason) {
    "account_limit" -> {
        val what = if (limit?.weekly == true) "Paused · weekly limit" else "Paused · limit"
        val on = accountName?.let { " on $it" } ?: ""
        val resets = relativeWithin(limit?.resetsAt, nowSeconds)?.let { " · resets in $it" } ?: ""
        "$what$on$resets"
    }
    "no_credentials" -> accountName?.let { "$it is signed out" } ?: "Signed out"
    "host_down" -> "${row.hostAlias.ifBlank { "Host" }} is down"
    else -> null
}

/** The limit [row]'s account is at now, from the usage readings; null when none is known. */
internal fun SessionsUiState.limitOf(row: SessionRow): AccountLimit? =
    row.accountUuid?.let(accountUsage::get)?.limitAt(nowSeconds)

/** "PR #476 ✓": the pull request and its CI in one chip, or "CI ✗" alone; null when neither. */
internal fun prChip(row: SessionRow): Pair<String, StatusWord?>? {
    val number = row.prUrl?.substringAfterLast('/')?.takeIf { n -> n.isNotEmpty() && n.all { it.isDigit() } }
    val (mark, word) = when (row.ciStatus) {
        "passing" -> " ✓" to StatusWord.DONE
        "failing" -> " ✗" to StatusWord.FAILED
        null -> "" to null
        else -> " …" to null
    }
    return when {
        number != null -> "PR #$number$mark" to word
        row.prUrl != null -> "PR$mark" to word
        row.ciStatus != null -> "CI${mark}" to word
        else -> null
    }
}

/** The New layout's name for each view: urgency is the State view there (MobileNav's "Group by"). */
internal fun groupModeLabel(mode: GroupMode): String = when (mode) {
    GroupMode.URGENCY -> "state"
    else -> mode.label
}

/** The views the New layout offers, in the sheet's order; Work only with the work graph. */
internal fun newGroupModes(workAvailable: Boolean): List<GroupMode> =
    listOf(GroupMode.URGENCY, GroupMode.HOST, GroupMode.PROJECT, GroupMode.WORK).filter { it != GroupMode.WORK || workAvailable }

/** "22 on 5 hosts", "1 on 1 host": the Sessions header while live. */
internal fun sessionsOnHosts(sessions: Int, hosts: Int): String = "$sessions on $hosts ${if (hosts == 1) "host" else "hosts"}"

/** The State view's bands, in the order a person deals with them. */
internal val BAND_ORDER: List<StatusWord?> =
    listOf(StatusWord.NEEDS_YOU, StatusWord.FAILED, StatusWord.WORKING, StatusWord.PAUSED, StatusWord.IDLE, StatusWord.DONE, null)

/** [rows] cut into the State view's bands, keeping their order inside each; empty bands dropped. */
internal fun stateBands(rows: List<SessionRow>): List<Pair<StatusWord?, List<SessionRow>>> {
    val byWord = rows.groupBy(::phoneWord)
    return BAND_ORDER.mapNotNull { w -> byWord[w]?.let { w to it } }
}

/** Every row the list holds, in the order it draws them. */
internal fun SessionsUiState.shownRows(): List<SessionRow> =
    urgent + groups.flatMap { h -> h.projects.flatMap { it.sessions } }

/** Where a search looks (MobileSessionsTools): every kind of hit, or one. */
enum class SearchScope(val label: String) { ALL("All"), SESSIONS("Sessions"), PROJECTS("Projects"), HOSTS("Hosts"), TICKETS("Tickets") }

// ── One session at phone size ──

/**
 * A session as a [PhoneRow]: dot, title and age, then the word and what it
 * waits on, then at most two chips. [showHost] for a list with no host
 * heading over the row (the State view, search, the Inbox).
 */
@Composable
fun PhoneSessionRow(
    row: SessionRow,
    nowSeconds: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = true,
    showHost: Boolean = false,
    showWork: Boolean = true,
    /** The age to show; the row's last activity unless the caller has a better stamp (the Inbox: when it asked). */
    since: Long? = row.lastActivityAt,
    selecting: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    divider: Boolean = true,
    /** The label of the account the session runs under, from `list_accounts`; null shows none (step 4.10). */
    accountName: String? = null,
    /** The limit that account is at, from `account_usage`; null when none is known. */
    limit: AccountLimit? = null,
) {
    val word = phoneWord(row)
    val chipList = rowChips(row, showHost, showWork, accountName)
    val chipRow: (@Composable RowScope.() -> Unit)? = if (chipList.isEmpty()) null else {
        { for ((text, tone) in chipList) OrbitChip(text, word = tone) }
    }
    PhoneRow(
        title = row.displayName,
        line = waitsOn(row, accountName, limit, nowSeconds),
        modifier = modifier,
        word = word,
        lead = phoneLead(word, live),
        age = relativeTime(since, nowSeconds),
        selected = selected,
        divider = divider,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = if (selecting) ({ PickBox(selected) }) else null,
        chips = chipRow,
    )
}

/**
 * A row's chips, two at most (PhoneRow): the pull request with its CI first,
 * then the host where no heading names it, else the ticket; the account
 * (step 4.10) takes a place left over, unless the row's line already names it.
 */
internal fun rowChips(
    row: SessionRow,
    showHost: Boolean,
    showWork: Boolean,
    accountName: String? = null,
): List<Pair<String, StatusWord?>> {
    val pr = prChip(row)
    val work = row.work?.takeIf { showWork }?.let { it.label to null }
    val host = row.hostAlias.takeIf { showHost && it.isNotBlank() }?.let { it to null }
    val chips = if (host != null) listOfNotNull(pr ?: work, host) else listOfNotNull(work, pr)
    val named = row.attention?.reason == "account_limit" || row.attention?.reason == "no_credentials"
    val account = accountName?.takeIf { it.isNotBlank() && !named }?.let { it to null }
    return if (account != null && chips.size < 2) chips + account else chips
}

/** The check box a row shows in its dot's place while rows are picked. */
@Composable
private fun PickBox(selected: Boolean) {
    val o = Fleet.colors
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .size(20.dp)
            .then(
                if (selected) Modifier.background(o.accent, RoundedCornerShape(4.dp))
                else Modifier.border(1.5.dp, o.controlBorder, RoundedCornerShape(4.dp)),
            )
            .semantics { contentDescription = if (selected) "Selected" else "Not selected" },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(FleetIcons.Check, contentDescription = null, tint = o.accentFg, modifier = Modifier.size(14.dp))
    }
}

// ── The New layout's Sessions tab ──

/**
 * The New layout's Sessions tab (MobileNav, MobileSessionsTools): every
 * session grouped by host with counts, the filters that are on as chips under
 * the title, one New session button, and the Orbit pull to refresh.
 *
 * What the Classic list had and where it went:
 *  - the pinned *N sessions need you* block is the Inbox tab now, so a
 *    needs-you row is listed once, under its host;
 *  - *Needs you* and *Group* chips: grouping lives in the filters sheet with
 *    the filters ("Filters and grouping"); a *Needs you* filter that is on
 *    shows as a chip like any other;
 *  - *Today*, Tickets, Missions and *Select sessions* sit under ⋮;
 *  - the agent's ✦ is the Control tab.
 *
 * Search, bulk select and its outcome are drawn here too; the view models
 * are the Classic list's, so a filter or a grouping chosen on one layout is
 * the other's after a switch or a restart.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsTab(
    state: SessionsUiState,
    handlers: SessionsHandlers,
    modifier: Modifier = Modifier,
    bulk: BulkUiState = BulkUiState(),
    bulkHandlers: BulkHandlers = BulkHandlers(),
    hits: SearchHits = SearchHits(),
) {
    val live = state.status is ConnectionStatus.Connected
    val rows = state.shownRows()
    val select: ((Long) -> Unit)? = if (bulk.enabled) handlers.onToggleSelect else null
    val tap: (Long) -> Unit = { id -> if (bulk.active && select != null) select(id) else handlers.onOpenSession(id) }
    var scope by remember { mutableStateOf(SearchScope.ALL) }
    var sending by remember { mutableStateOf(false) }
    var killing by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        when {
            bulk.active -> SelectionHeader(bulk, onClear = handlers.onClearSelection, onSelectAll = { bulkHandlers.onSelectAll(rows.map { it.id }) })
            state.searchOpen -> SearchHeader(
                query = state.filters.query,
                onSetQuery = handlers.onSetQuery,
                onClose = handlers.onToggleSearch,
                scope = scope,
                onScope = { scope = it },
                counts = searchCounts(rows.size, hits),
            )
            else -> TabHeader(state, handlers, canSelect = bulk.enabled, hostCount = rows.mapTo(HashSet()) { it.hostAlias }.size)
        }
        HiddenAttentionBanner(count = state.hiddenAttention, onClearAll = handlers.onClearAll)
        HubBanner(rememberPhoneConnection(state.status), asOf = state.staleAt, onRetry = handlers.onRefresh)
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            OrbitPullToRefresh(
                isRefreshing = state.refreshing,
                onRefresh = handlers.onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    val searching = state.searchOpen && state.filters.query.isNotBlank()
                    if (searching) {
                        searchSections(state, rows, hits, scope, handlers, live, tap, bulk, select)
                    } else {
                        if (state.isEmpty) {
                            item(key = "empty") {
                                EmptyFleet(
                                    state = state,
                                    onNewSession = handlers.onNewSession,
                                    onClearAll = handlers.onClearAll,
                                    onSetShowArchived = handlers.onSetShowArchived,
                                    modifier = Modifier.fillParentMaxSize(),
                                )
                            }
                        }
                        // The State view: one band per status word, worst first.
                        for ((word, band) in stateBands(state.urgent)) {
                            stickyHeader(key = "band-${word?.name ?: "unknown"}") {
                                SectionHeading(word?.label ?: "Unknown", band.size)
                            }
                            items(band, key = { "urgent-${it.id}" }) { row ->
                                PhoneSessionRow(
                                    row = row,
                                    nowSeconds = state.nowSeconds,
                                    live = live,
                                    showHost = true,
                                    accountName = row.accountUuid?.let(state.accountNames::get),
                                    limit = state.limitOf(row),
                                    onClick = { tap(row.id) },
                                    selecting = bulk.active,
                                    selected = row.id in bulk.selected,
                                    onLongClick = select?.let { { it(row.id) } },
                                )
                            }
                        }
                        for (host in state.groups) {
                            stickyHeader(key = "host-${host.alias}") {
                                HostHeading(host, onClick = { handlers.onToggleHost(host.alias) })
                            }
                            if (host.collapsed) continue
                            for (project in host.projects) {
                                val work = project.work
                                val heading = when {
                                    work != null -> if (work.key != null && work.title.isNotBlank()) "${work.label} · ${work.title}" else work.label
                                    else -> project.label
                                }
                                if (heading.isNotEmpty()) {
                                    item(key = "${host.alias}-${project.id}") { SubHeading(heading, project.attentionCount) }
                                }
                                items(project.sessions, key = { it.id }) { row ->
                                    PhoneSessionRow(
                                        row = row,
                                        nowSeconds = state.nowSeconds,
                                        live = live,
                                        showWork = work == null,
                                        accountName = row.accountUuid?.let(state.accountNames::get),
                                        limit = state.limitOf(row),
                                        onClick = { tap(row.id) },
                                        selecting = bulk.active,
                                        selected = row.id in bulk.selected,
                                        onLongClick = select?.let { { it(row.id) } },
                                    )
                                }
                            }
                        }
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
                }
            }
            // One New session button (it was two buttons); the agent's ✦ is the Control tab.
            if (!bulk.active) {
                handlers.onNewSession?.let { newSession ->
                    ExtendedFloatingActionButton(
                        onClick = newSession,
                        containerColor = Fleet.colors.accent,
                        contentColor = Fleet.colors.accentFg,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        icon = { Icon(FleetIcons.Add, contentDescription = null) },
                        text = { Text("New session") },
                    )
                }
            }
        }
        if (bulk.active) {
            BulkActionBar(
                bulk = bulk,
                onSend = { sending = true },
                onKill = { killing = true },
            )
        }
    }

    if (sending) {
        SendSheet(count = bulk.selected.size, onDismiss = { sending = false }, onSend = { handlers.onBulkSend(it); sending = false })
    }
    if (killing) {
        val byId = rows.associateBy { it.id }
        val picked = bulk.selected.mapNotNull(byId::get)
        KillSheet(
            killable = picked.filter { killRefusal(it) == null }.map { it.displayName },
            skipped = bulk.selected.size - bulk.killable,
            onDismiss = { killing = false },
            onKill = { handlers.onBulkKill(); killing = false },
        )
    }
    bulk.outcome?.let { outcome ->
        BulkOutcomeSheet(
            outcome = outcome,
            action = bulk.action,
            running = bulk.running,
            onRetry = bulkHandlers.onRetry,
            onRetryAll = bulkHandlers.onRetryAll,
            onDismiss = handlers.onDismissBulkOutcome,
        )
    }
}

/** What the bulk outcome and selection bar report beyond [SessionsHandlers]. */
data class BulkHandlers(
    val onSelectAll: (List<Long>) -> Unit = {},
    val onRetry: (Long) -> Unit = {},
    val onRetryAll: () -> Unit = {},
)

/** The tab's title, its live line, search and ⋮, and the filter chips under them. */
@Composable
private fun TabHeader(state: SessionsUiState, handlers: SessionsHandlers, canSelect: Boolean, hostCount: Int) {
    val subtitle = when (state.status) {
        is ConnectionStatus.Connected -> sessionsOnHosts(state.shown, hostCount)
        is ConnectionStatus.Reconnecting -> "reconnecting…"
        is ConnectionStatus.Offline -> "offline"
        is ConnectionStatus.Refused -> "refused"
    }
    // Everything that narrows the list, Needs you included: the New tab has
    // no Needs you chip of its own (that is the Inbox), so a Needs you filter
    // carried over from Classic must still be on screen to turn off.
    val facets = state.facets.filterNot { it.id == SessionFacetId.SEARCH }
    ScreenHeader(
        title = "Sessions",
        subtitle = subtitle,
        actions = {
            IconButton(onClick = handlers.onToggleSearch) { Icon(FleetIcons.Search, contentDescription = "Search sessions") }
            val menu = listOfNotNull(
                handlers.onOpenToday?.let { "Today" to it },
                handlers.onOpenTickets?.let { "Tickets" to it },
                handlers.onOpenMissions?.let { "Missions" to it },
                handlers.onStartSelect.takeIf { canSelect }?.let { "Select sessions" to it },
            )
            if (menu.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) { Icon(FleetIcons.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for ((label, action) in menu) {
                            DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
                        }
                    }
                }
            }
        },
        below = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FiltersButton(count = facets.size, onClick = handlers.onOpenFilters)
                // A view, not a filter: it opens the same sheet, whose first
                // group is "Group by".
                FilterChip(
                    selected = false,
                    onClick = handlers.onOpenFilters,
                    label = { Text("Group: ${groupModeLabel(state.groupMode)} ▾") },
                )
            }
            FilterStrip(
                facets = facets,
                onClear = handlers.onClearFacet,
                onClearAll = handlers.onClearAll,
                lead = if (state.shown != state.total) "${state.shown} of ${state.total}" else null,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        },
    )
}

/** A host's heading: chevron, name, how many sessions and how many need you, or that it is offline. */
@Composable
private fun HostHeading(host: HostGroup, onClick: () -> Unit) {
    val o = Fleet.colors
    Surface(color = o.bg) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = OrbitTokens.spacing("phone-gutter").dp)
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                FleetIcons.ArrowBack,
                contentDescription = if (host.collapsed) "Unfold ${host.alias}" else "Fold ${host.alias} away",
                modifier = Modifier.size(16.dp).rotate(if (host.collapsed) 180f else -90f),
                tint = o.fgMuted,
            )
            Text(host.alias, color = o.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Count(host.sessionCount)
            Spacer(Modifier.weight(1f))
            when {
                host.reachable == false -> Text("unreachable", color = o.fgMuted, fontSize = 13.sp)
                host.attentionCount > 0 -> Text("${host.attentionCount} need${if (host.attentionCount == 1) "s" else ""} you", color = o.statusWaiting, fontSize = 13.sp)
            }
        }
    }
}

/** A band or section heading with its count: "Needs you 3". */
@Composable
private fun SectionHeading(title: String, count: Int) {
    val o = Fleet.colors
    Surface(color = o.bg) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .padding(horizontal = OrbitTokens.spacing("phone-gutter").dp)
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = o.fgMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Count(count)
        }
    }
}

/** A project or work heading under a host. */
@Composable
private fun SubHeading(text: String, attention: Int) {
    val o = Fleet.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 44.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = o.fgMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (attention > 0) Text("$attention", color = o.statusWaiting, fontSize = 12.sp)
    }
}

@Composable
private fun Count(n: Int) {
    val o = Fleet.colors
    Text(
        "$n",
        color = o.fg2,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.background(o.countBg, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp),
    )
}

// ── Search ──

/** How many hits each scope chip carries; Tickets has no count, it is a lookup. */
internal fun searchCounts(sessions: Int, hits: SearchHits): Map<SearchScope, Int?> = mapOf(
    SearchScope.ALL to null,
    SearchScope.SESSIONS to sessions,
    SearchScope.PROJECTS to hits.projects.size,
    SearchScope.HOSTS to hits.hosts.size,
    SearchScope.TICKETS to null,
)

/** The search field in the title's place, ✕ to close it, and the scope chips under it. */
@Composable
private fun SearchHeader(
    query: String,
    onSetQuery: (String) -> Unit,
    onClose: () -> Unit,
    scope: SearchScope,
    onScope: (SearchScope) -> Unit,
    counts: Map<SearchScope, Int?>,
) {
    val focus = LocalFocusManager.current
    Surface(color = Fleet.colors.bgPane) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onSetQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search sessions, projects, tickets…") },
                    singleLine = true,
                    leadingIcon = { Icon(FleetIcons.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                )
                IconButton(onClick = onClose) { Icon(FleetIcons.Close, contentDescription = "Close search") }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SearchScope.entries.toList(), key = { it.name }) { s ->
                    val n = counts[s]
                    FilterChip(
                        selected = s == scope,
                        onClick = { onScope(s) },
                        label = { Text(if (n != null && query.isNotBlank()) "${s.label} $n" else s.label) },
                    )
                }
            }
        }
    }
}

/** The search's sections, each under its own heading, as [scope] allows. */
private fun LazyListScope.searchSections(
    state: SessionsUiState,
    rows: List<SessionRow>,
    hits: SearchHits,
    scope: SearchScope,
    handlers: SessionsHandlers,
    live: Boolean,
    tap: (Long) -> Unit,
    bulk: BulkUiState,
    select: ((Long) -> Unit)?,
) {
    fun on(s: SearchScope) = scope == SearchScope.ALL || scope == s
    if (on(SearchScope.SESSIONS)) {
        item(key = "search-sessions") { SectionHeading("Sessions", rows.size) }
        if (rows.isEmpty()) {
            item(key = "search-sessions-none") { SearchNote("No session matches.") }
        }
        items(rows, key = { "search-${it.id}" }) { row ->
            PhoneSessionRow(
                row = row,
                nowSeconds = state.nowSeconds,
                live = live,
                showHost = true,
                accountName = row.accountUuid?.let(state.accountNames::get),
                limit = state.limitOf(row),
                onClick = { tap(row.id) },
                selecting = bulk.active,
                selected = row.id in bulk.selected,
                onLongClick = select?.let { { it(row.id) } },
            )
        }
    }
    if (on(SearchScope.PROJECTS) && hits.projects.isNotEmpty()) {
        item(key = "search-projects") { SectionHeading("Projects", hits.projects.size) }
        items(hits.projects, key = { "search-project-${it.id}" }) { project ->
            PhoneRow(
                title = project.label,
                line = if (handlers.onNewSession != null) "New session in this project" else "Project",
                dot = false,
                lead = null,
                onClick = if (handlers.onNewSession != null) ({ handlers.onSearchProject(project.id) }) else null,
            )
        }
    }
    if (on(SearchScope.HOSTS) && hits.hosts.isNotEmpty()) {
        item(key = "search-hosts") { SectionHeading("Hosts", hits.hosts.size) }
        items(hits.hosts, key = { "search-host-${it.alias}" }) { host ->
            PhoneRow(
                title = host.alias,
                line = if (host.reachable) "Its sessions" else "Unreachable · its sessions",
                dot = false,
                lead = null,
                onClick = { handlers.onSearchHost(host.alias) },
            )
        }
    }
    if (on(SearchScope.TICKETS) && hits.ticket) {
        item(key = "search-tickets") { SectionHeading("Tickets", 1) }
        item(key = "search-ticket") {
            PhoneRow(
                title = "Find ticket “${hits.query}”",
                line = "A key, a link or words",
                dot = false,
                lead = null,
                onClick = { handlers.onSearchTicket(hits.query) },
            )
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Text(text, color = Fleet.colors.fgMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

// ── Bulk select ──

/** In the title's place while rows are picked: ✕, how many, and Select all. */
@Composable
private fun SelectionHeader(bulk: BulkUiState, onClear: () -> Unit, onSelectAll: () -> Unit) {
    val o = Fleet.colors
    Surface(color = o.accentSoft, contentColor = o.fg) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) { Icon(FleetIcons.Close, contentDescription = "Clear selection") }
            Column(modifier = Modifier.weight(1f)) {
                Text(if (bulk.selected.isEmpty()) "Pick sessions" else "${bulk.selected.size} selected", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Tap a row to pick it", color = o.fgMuted, fontSize = 12.sp)
            }
            TextButton(onClick = onSelectAll) { Text("Select all") }
        }
    }
}

/** The actions at the bottom, in thumb reach: Send a message, and Kill… which asks again. */
@Composable
private fun BulkActionBar(bulk: BulkUiState, onSend: () -> Unit, onKill: () -> Unit) {
    val o = Fleet.colors
    Surface(color = o.bgPane) {
        Column {
            HorizontalDivider(color = o.border)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onSend, enabled = !bulk.running && bulk.selected.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text("Send a message")
                }
                TextButton(onClick = onKill, enabled = !bulk.running && bulk.killable > 0, modifier = Modifier.weight(1f)) {
                    Text("Kill…", color = if (!bulk.running && bulk.killable > 0) o.danger else o.fgMuted)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendSheet(count: Int, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    BottomSheet(
        title = "Send to ${sessionsWord(count)}",
        meta = "The same message, to each.",
        onDismiss = onDismiss,
        primary = SheetAction("Send", enabled = text.isNotBlank()) { onSend(text) },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Rebase on main and push") },
        )
    }
}

/** Kill asks again and names the sessions (MobileSessionsTools), and says what it skips. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KillSheet(killable: List<String>, skipped: Int, onDismiss: () -> Unit, onKill: () -> Unit) {
    BottomSheet(
        title = "Kill ${sessionsWord(killable.size)}?",
        meta = "Each is killed now, without waiting for it to save anything." +
            if (skipped > 0) " $skipped picked cannot be killed from here and are skipped." else "",
        onDismiss = onDismiss,
        primary = SheetAction("Kill ${killable.size}", enabled = killable.isNotEmpty(), onClick = onKill),
    ) {
        Column(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            for (name in killable) Text(name, color = Fleet.colors.fg, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

/** "Sent to 3 sessions": the outcome's title, naming the action. */
internal fun bulkOutcomeTitle(action: BulkAction?, count: Int): String = when (action) {
    is BulkAction.Send -> "Sent to ${sessionsWord(count)}"
    BulkAction.Kill -> "Killed ${sessionsWord(count)}"
    null -> "Done for ${sessionsWord(count)}"
}

/** "2 delivered, 1 not delivered" (or "killed"): the outcome's line. */
internal fun bulkOutcomeLine(action: BulkAction?, outcome: List<BulkOutcome>): String {
    val ok = outcome.count { it.ok }
    val verb = if (action == BulkAction.Kill) "killed" else "delivered"
    val failed = outcome.size - ok
    return if (failed == 0) "$ok $verb" else "$ok $verb, $failed not $verb"
}

/**
 * The bulk outcome as a sheet (it was a dialog with OK only): one line per
 * session, what did not go through with its reason and Retry, and Retry for
 * all of them at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkOutcomeSheet(
    outcome: List<BulkOutcome>,
    action: BulkAction?,
    running: Boolean,
    onRetry: (Long) -> Unit,
    onRetryAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val o = Fleet.colors
    val retryable = outcome.filter { !it.ok && it.retryable }
    val verb = if (action == BulkAction.Kill) "killed" else "delivered"
    BottomSheet(
        title = bulkOutcomeTitle(action, outcome.size),
        meta = ((action as? BulkAction.Send)?.let { "“${it.text}” · " } ?: "") + bulkOutcomeLine(action, outcome),
        onDismiss = onDismiss,
        cancelLabel = "Done",
        primary = if (retryable.isEmpty()) null else SheetAction("Retry ${retryable.size} not $verb", enabled = !running, onClick = onRetryAll),
    ) {
        Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            for (x in outcome) {
                Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (x.ok) "✓" else "✗", color = if (x.ok) o.statusDone else o.statusFailed, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(x.name, color = o.fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (x.ok) verb.replaceFirstChar { it.uppercaseChar() } else x.reason ?: "Not $verb",
                            color = o.fgMuted,
                            fontSize = 13.sp,
                            maxLines = 2,
                        )
                    }
                    if (!x.ok && x.retryable) TextButton(onClick = { onRetry(x.sessionId) }, enabled = !running) { Text("Retry") }
                }
            }
        }
    }
}
