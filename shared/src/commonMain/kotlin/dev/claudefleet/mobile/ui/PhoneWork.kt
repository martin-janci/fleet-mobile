package dev.claudefleet.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.GroupSource
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.PastWorkSummary
import dev.claudefleet.mobile.model.ReviewItem
import dev.claudefleet.mobile.model.ReviewKind
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.TaskKind
import dev.claudefleet.mobile.model.WorkFacetId
import dev.claudefleet.mobile.model.WorkTask
import dev.claudefleet.mobile.model.WorkTaskLink
import dev.claudefleet.mobile.model.orgColorArgb
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.MarkdownText
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.HubBanner
import dev.claudefleet.mobile.ui.kit.OrbitChip
import dev.claudefleet.mobile.ui.kit.OrbitPullToRefresh
import dev.claudefleet.mobile.ui.kit.PhoneRow
import dev.claudefleet.mobile.ui.kit.StatusWord
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.kit.rememberPhoneConnection
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import dev.claudefleet.mobile.ui.theme.StatusTone
import dev.claudefleet.mobile.ui.kit.LoadFailed
import dev.claudefleet.mobile.ui.kit.loadFailedTitle

// ── Words a task row says (MobileWork) ──

/**
 * The status word of one session under a task. A live session reads what its
 * own row says ([phoneWord]) when the phone has the row; otherwise what the
 * link carries. An ended session reads Failed when it ended failing, Done
 * when it finished, and no word ("Ended") for one that was killed or left
 * the task; a rejected suggestion has none either.
 */
internal fun linkWord(link: WorkTaskLink, row: SessionRow?): StatusWord? = when (link.state) {
    LinkState.Active, LinkState.Suggested -> row?.let(::phoneWord)
        ?: if (link.needsYou) StatusWord.NEEDS_YOU else StatusWord.of(StatusTone.of(link.claudeStatus, null))
    LinkState.Ended -> endedWord(link.endReason)
    LinkState.Rejected, LinkState.Unknown -> null
}

private fun endedWord(reason: String?): StatusWord? {
    val r = reason?.lowercase().orEmpty()
    return when {
        r.contains("fail") || r.contains("crash") || r.contains("error") -> StatusWord.FAILED
        r.isEmpty() || r == "done" || r == "completed" || r == "finished" || r.contains("merged") -> StatusWord.DONE
        else -> null
    }
}

/** How a past session's card opens: "Done", "Failed", or "Ended · killed". */
internal fun pastLead(link: WorkTaskLink): String = when (val w = linkWord(link, null)) {
    null -> listOfNotNull("Ended", link.endReason?.takeIf { it.isNotBlank() }).joinToString(" · ")
    else -> w.label
}

/** A status word as it reads inside a count: "1 needs you", "2 working". */
private fun countWords(word: StatusWord, n: Int): String = "$n ${word.label.lowercase()}"

/**
 * What a task row says on its second line: the status words of its live
 * sessions first ("1 needs you · 1 working"), coloured by the most urgent of
 * them, then the tracker's own status. A task with no live session says what
 * it last had ("PR #118 · 1 past session") or "No session yet". Red is only
 * ever Failed; amber only Needs you.
 *
 * A blocked task (it waits on another task that is not done, claude-fleet
 * 6.3) reads Needs you with its reason, "Blocked on FLEET-12", as every
 * Blocked does on the phone; a live session's own word still leads, and the
 * reason follows it. [labelOf] names a blocker from the loaded tasks.
 */
data class TaskLine(val word: StatusWord?, val lead: String?, val line: String)

internal fun taskLine(task: WorkTask, rowOf: (Long) -> SessionRow?, labelOf: (String) -> String? = { null }): TaskLine {
    val live = task.sessions.filter { it.state == LinkState.Active }
    val words = live.mapNotNull { link -> linkWord(link, link.sessionId?.let(rowOf)) }
    val counts = BAND_ORDER.filterNotNull().mapNotNull { w -> words.count { it == w }.takeIf { it > 0 }?.let { w to it } }
    val blocked = blockedLine(task, labelOf)
    val word = counts.firstOrNull()?.first
        ?: if (task.needsYou || blocked != null) StatusWord.NEEDS_YOU else null
    val quiet = counts.isEmpty() && !task.needsYou && task.counts.active == 0
    val lead = when {
        counts.isNotEmpty() -> counts.joinToString(" · ") { (w, n) -> countWords(w, n) }
        task.needsYou -> "Needs you"
        task.counts.active > 0 -> "${task.counts.active} active"
        else -> blocked
    }
    val rest = buildList {
        if (blocked != null && blocked != lead) add(blocked)
        if (quiet) {
            val past = task.sessions.filter { it.state == LinkState.Ended }
            val pr = past.firstNotNullOfOrNull { it.prUrl?.takeIf { u -> u.isNotBlank() } }
            if (pr != null) add(prLabel(pr))
            val ended = maxOf(task.counts.ended, past.size)
            if (ended > 0) add("$ended past session${if (ended == 1) "" else "s"}") else add("No session yet")
        }
        task.statusName?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (task.unavailable) add("unavailable")
        if (task.trackerDown) add("tracker down")
    }
    return TaskLine(word, lead, rest.joinToString(" · "))
}

/**
 * "Blocked on FLEET-12", "Blocked on FLEET-12 and 2 more", or null for a task
 * that waits on nothing. A blocker the phone has not loaded (or may not see:
 * the hub leaves those out of `blocked_by`) is counted, never named.
 */
internal fun blockedLine(task: WorkTask, labelOf: (String) -> String?): String? {
    if (!task.blocked) return null
    val n = task.blockedBy.size
    val named = task.blockedBy.firstNotNullOfOrNull(labelOf)
    return when {
        named != null && n == 1 -> "Blocked on $named"
        named != null -> "Blocked on $named and ${n - 1} more"
        n > 1 -> "Blocked on $n tasks"
        n == 1 -> "Blocked on another task"
        else -> "Blocked"
    }
}

/** Live sessions, across the loaded tasks, whose word is Needs you. */
internal fun sessionsNeedingYou(tasks: List<WorkTask>, rowOf: (Long) -> SessionRow?): Int =
    tasks.flatMap { t -> t.sessions.filter { it.state == LinkState.Active } }
        .distinctBy { it.sessionId ?: -it.linkId }
        .count { linkWord(it, it.sessionId?.let(rowOf)) == StatusWord.NEEDS_YOU }

/** "7 tasks · 3 sessions need you", "1 task". Offline, the stale line instead. */
internal fun myWorkSubtitle(total: Int, needYou: Int): String = buildString {
    append("$total task${if (total == 1) "" else "s"}")
    if (needYou > 0) append(" · $needYou session${if (needYou == 1) " needs" else "s need"} you")
}

/** "FLEET-142 Hosts screen: last ping": a row's title, key first. */
internal fun taskTitle(task: WorkTask): String =
    if (task.key.isNullOrBlank()) task.title.ifBlank { task.taskId } else listOf(task.key, task.title).filter { it.isNotBlank() }.joinToString(" ")

/** Where a group came from, in the chip's few words: "by rule", "by hand", "from the tracker". */
internal fun groupChipWords(source: GroupSource): String? = when (source) {
    GroupSource.Rule -> "by rule"
    GroupSource.Manual -> "by hand"
    GroupSource.Tracker -> "from the tracker"
    GroupSource.Repo -> "from the repository"
    GroupSource.Key -> "from the key"
    GroupSource.None, GroupSource.Unknown -> null
}

/** "2 past sessions · PR #101", the fold over a task's past sessions. */
internal fun pastFoldLine(past: List<WorkTaskLink>): String = buildList {
    add("${past.size} past session${if (past.size == 1) "" else "s"}")
    past.firstNotNullOfOrNull { it.prUrl?.takeIf { u -> u.isNotBlank() } }?.let { add(prLabel(it)) }
}.joinToString(" · ")

/** The drafted mark under an inline summary: who wrote it, from what. */
internal fun draftedLine(summary: PastWorkSummary): String = buildString {
    append("Drafted")
    summary.model.takeIf { it.isNotBlank() }?.let { append(" by $it") }
    append(" from this session's transcript")
    if (summary.truncated) append(", cut short")
}

/** The bulk action at the end of To review: only ordinary suggestions, never a cross-organisation link. */
internal fun linkAllLabel(n: Int): String = "Link the $n ordinary suggestion${if (n == 1) "" else "s"}"

// ── My work ──

/**
 * **My work** on the New bar (MobileWork): one row of chips (saved views,
 * Mine, To review with its count, Filters), org sections with their colour
 * swatch, group sub-headers, and a [PhoneRow] per task that leads with what
 * its sessions are doing. A task with no session offers Start right on the
 * row. Offline the last page stays under the hub banner, once.
 *
 * Everything Classic's My work does is still here: search by key or title,
 * saved views, the filters sheet (with Assigned to me and the review filter
 * inside it), placement rules, folding, *Load more* and archived tasks.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun PhoneMyWorkScreen(
    state: MyWorkUiState,
    status: ConnectionStatus,
    nowSeconds: Long,
    rowOf: (Long) -> SessionRow?,
    handlers: MyWorkHandlers = MyWorkHandlers(),
    reviewCount: Int = state.reviewCount,
    /** Start on a task with no session: the New session wizard with the key. Null hides it. */
    onStart: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val o = Fleet.colors
    val tasks = remember(state.orgs) { state.orgs.flatMap { org -> org.groups.flatMap { it.tasks } } }
    val needYou = sessionsNeedingYou(tasks, rowOf)
    val labels = remember(tasks) { tasks.associate { it.taskId to it.label } }
    val connection = rememberPhoneConnection(status)
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "My work",
            subtitle = if (state.loaded) {
                if (!state.filters.query.isNullOrBlank()) "${state.total} match “${state.filters.query}”" else myWorkSubtitle(state.total, needYou)
            } else null,
            actions = {
                if (state.canCreateTask) {
                    IconButton(onClick = handlers.onOpenNewTask) { Icon(FleetIcons.Add, contentDescription = "New task") }
                }
                IconButton(onClick = handlers.onToggleSearch) {
                    Icon(if (state.searchOpen) FleetIcons.Close else FleetIcons.Search, contentDescription = if (state.searchOpen) "Close search" else "Search tasks")
                }
            },
            below = {
                if (state.searchOpen) {
                    OutlinedTextField(
                        value = state.filters.query.orEmpty(),
                        onValueChange = handlers.onSetQuery,
                        label = { Text("Key or title") },
                        singleLine = true,
                        trailingIcon = if (state.filters.query.isNullOrEmpty()) null else {
                            { IconButton(onClick = { handlers.onSetQuery("") }) { Icon(FleetIcons.Close, contentDescription = "Clear search") } }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = OrbitTokens.spacing("phone-gutter").dp),
                ) {
                    if (state.viewsAvailable) {
                        items(state.views, key = { "v-${it.id}" }) { view ->
                            FilterChip(
                                selected = view.id == state.activeViewId,
                                onClick = { handlers.onApplyView(view) },
                                label = { Text(view.name, maxLines = 1) },
                            )
                        }
                    }
                    item(key = "mine") {
                        FilterChip(selected = state.filters.mine == true, onClick = handlers.onToggleMine, label = { Text("Mine") })
                    }
                    handlers.onOpenReview?.let { open ->
                        item(key = "review") {
                            FilterChip(
                                selected = false,
                                onClick = open,
                                label = { Text(if (reviewCount > 0) "To review $reviewCount" else "To review") },
                            )
                        }
                    }
                    handlers.onOpenPullRequests?.let { open ->
                        item(key = "prs") {
                            FilterChip(selected = false, onClick = open, label = { Text("Pull requests") })
                        }
                    }
                    item(key = "filters") {
                        val n = state.filters.sheetCount + if (state.filters.review == true) 1 else 0
                        FilterChip(
                            selected = n > 0,
                            onClick = handlers.onOpenFilters,
                            leadingIcon = { Icon(FleetIcons.Filters, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            label = { Text(if (n > 0) "Filters ($n)" else "Filters") },
                        )
                    }
                }
                FilterStrip(
                    facets = state.facets.filter { it.id != WorkFacetId.MINE && it.id != WorkFacetId.QUERY },
                    onClear = handlers.onClearFacet,
                    onClearAll = handlers.onClearFilters,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            },
        )
        HubBanner(connection, asOf = state.stale?.substringAfter("as of ", "")?.takeIf { it.isNotBlank() }, onRetry = handlers.onRefresh)
        // The first read failed: Retry runs it again (a conflict has its own Reload row).
        ErrorBanner(state.error, onDismiss = handlers.onDismissError, onRetry = handlers.onRefresh.takeIf { !state.loaded && !state.conflict })
        if (state.error != null && state.conflict) ReloadRow(handlers.onReload)

        OrbitPullToRefresh(
            isRefreshing = state.loading,
            onRefresh = handlers.onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.isEmpty) {
                    item(key = "empty") { PhoneEmptyWork(state, handlers, Modifier.fillParentMaxSize()) }
                } else if (!state.loaded && !state.loading && state.error != null) {
                    item(key = "failed") { LoadFailed("your work", state.error?.body, handlers.onRefresh) }
                }
                for (org in state.orgs) {
                    stickyHeader(key = org.key) {
                        PhoneOrgHeader(org.name, org.color, org.count, org.collapsed, onClick = { handlers.onToggleSection(org.key) })
                    }
                    if (org.collapsed) continue
                    for (group in org.groups) {
                        // One group with no name under an org says nothing: skip its header.
                        val header = !(org.groups.size == 1 && group.group.source == GroupSource.None)
                        if (header) {
                            item(key = "g-${group.key}") {
                                PhoneGroupHeader(group.group.title, group.count, group.collapsed, onClick = { handlers.onToggleSection(group.key) })
                            }
                        }
                        if (group.collapsed) continue
                        items(group.tasks, key = { "t-${group.key}-${it.taskId}" }) { task ->
                            PhoneTaskRow(
                                task = task,
                                nowSeconds = nowSeconds,
                                rowOf = rowOf,
                                onClick = { handlers.onOpenTask(task.taskId) },
                                labelOf = labels::get,
                                onStart = onStart?.takeIf { task.counts.active == 0 && task.sessions.none { it.state == LinkState.Active } }
                                    ?.let { start -> task.key?.takeIf { it.isNotBlank() }?.let { key -> { start(key) } } },
                            )
                        }
                        if (group.hasMore || group.error != null || group.loadingMore) {
                            item(key = "more-${group.key}") {
                                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    // Titled like the list's own failed load, with the plain sentence under it; Show more retries.
                                    group.error?.let {
                                        Text(loadFailedTitle("more tasks"), color = o.fg, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                                        Text(it.body, color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
                                    }
                                    if (group.loadingMore) {
                                        if (rememberLoaderVisible(true)) DotWave()
                                    } else if (group.hasMore) {
                                        TextButton(onClick = { handlers.onLoadMore(group.key) }, enabled = state.connected) {
                                            Text("Show more (${group.tasks.size} of ${group.count})", color = o.accent)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.archivedRow && !state.isEmpty) {
                    item(key = "archived") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (state.showArchived) "Archived tasks are shown" else "${state.archivedHidden} archived",
                                color = o.fgMuted,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { handlers.onSetArchived(!state.showArchived) }) {
                                Text(if (state.showArchived) "Hide" else "Show", color = o.accent)
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.filtersOpen) WorkFiltersSheet(state, handlers, quickToggles = true)
    if (state.rulesOpen) RulesSheet(state.rules, handlers.onCloseRules)
    NewTaskSheetFor(state, handlers)
}

/** One task as a [PhoneRow]: key and title, its sessions' words, its age, and Start when nothing works on it. */
@Composable
internal fun PhoneTaskRow(
    task: WorkTask,
    nowSeconds: Long,
    rowOf: (Long) -> SessionRow?,
    onClick: () -> Unit,
    onStart: (() -> Unit)? = null,
    /** A blocker's name ("FLEET-12") from the loaded tasks, for "Blocked on …". */
    labelOf: (String) -> String? = { null },
) {
    val line = taskLine(task, rowOf, labelOf)
    val chips = buildList {
        if (task.review) add("Suggested link")
        if (task.orgMixed) add("Two organisations")
    }
    val chipRow: (@Composable RowScope.() -> Unit)? = if (chips.isEmpty() && onStart == null) null else {
        {
            for (c in chips) OrbitChip(c)
            if (onStart != null) {
                Text(
                    "Start a session",
                    color = Fleet.colors.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clickable(onClick = onStart)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
    }
    PhoneRow(
        title = taskTitle(task),
        line = line.line,
        word = line.word,
        lead = line.lead,
        separator = " · ",
        age = relativeTime(task.lastActivityAt, nowSeconds),
        onClick = onClick,
        chips = chipRow,
    )
}

/** A PR chip for a row, or none. */
private fun prChipOf(url: String?): (@Composable RowScope.() -> Unit)? {
    val label = url?.takeIf { it.isNotBlank() }?.let(::prLabel) ?: return null
    return { OrbitChip(label) }
}

/** The org's colour as a dot, or nothing for an org with no colour (never a guessed one). */
@Composable
internal fun OrgSwatch(color: String?, size: Int = 10) {
    val argb = orgColorArgb(color) ?: return
    Box(modifier = Modifier.size(size.dp).background(Color(argb), CircleShape))
}

@Composable
private fun PhoneOrgHeader(name: String, color: String?, count: Int, collapsed: Boolean, onClick: () -> Unit) {
    val o = Fleet.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(o.bg)
            .heightIn(min = OrbitTokens.spacing("touch-min").dp)
            .clickable(onClick = onClick)
            .padding(horizontal = OrbitTokens.spacing("phone-gutter").dp)
            .semantics { contentDescription = "$name, $count tasks, ${if (collapsed) "folded" else "open"}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OrgSwatch(color)
        Text(name, color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("$count", color = o.fgMuted, fontSize = 13.sp)
        Fold(collapsed)
    }
}

@Composable
private fun PhoneGroupHeader(title: String, count: Int, collapsed: Boolean, onClick: () -> Unit) {
    val o = Fleet.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTokens.spacing("touch-min").dp)
            .clickable(onClick = onClick)
            .padding(start = 34.dp, end = OrbitTokens.spacing("phone-gutter").dp)
            .semantics { contentDescription = "$title, $count tasks, ${if (collapsed) "folded" else "open"}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = o.fgMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count", color = o.fgMuted, fontSize = 12.sp)
        Fold(collapsed)
    }
}

@Composable
private fun Fold(collapsed: Boolean) {
    Icon(
        FleetIcons.ChevronDown,
        contentDescription = null,
        tint = Fleet.colors.fgMuted,
        modifier = Modifier.size(18.dp).rotate(if (collapsed) -90f else 0f),
    )
}

@Composable
private fun PhoneEmptyWork(state: MyWorkUiState, handlers: MyWorkHandlers, modifier: Modifier) {
    val o = Fleet.colors
    Column(
        modifier = modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        when {
            // Filters hide everything: say which, and the way out.
            state.facets.isNotEmpty() -> {
                Text("Nothing matches", color = o.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(emptyWorkSentence(dev.claudefleet.mobile.model.facetSentence(state.facets)), color = o.fgMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
                OutlinedButton(onClick = handlers.onClearFilters) { Text("Clear filters") }
            }
            state.archivedHidden > 0 -> {
                Text("Everything here is archived", color = o.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { handlers.onSetArchived(true) }) { Text("Show ${state.archivedHidden} archived") }
            }
            else -> {
                Text("No tasks here", color = o.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tasks appear when a tracker is connected on the hub, or a session is linked to work.",
                    color = o.fgMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ── One task ──

/** What the New task screen reports on top of [TaskHandlers]. */
data class PhoneTaskHandlers(
    val task: TaskHandlers = TaskHandlers(),
    val onLink: (WorkTaskLink) -> Unit = {},
    val onNotThis: (WorkTaskLink) -> Unit = {},
    val onClearSummary: (Long) -> Unit = {},
)

/**
 * One task on the New bar (MobileWork): the tracker's fields as chips (its
 * status, the organisation with its swatch, the group and where it came
 * from), the description rendered, then its sessions with their status words.
 * A suggested session is answered right here (Link, Not this), labelled
 * Suggested with the reason, never as Jev. Past sessions fold under one line;
 * each summary appears inline on its card, marked Drafted with its source,
 * Regenerate and Clear, instead of a dialog. The actions sit last: Continue,
 * Start new here, Open the primary session, Place in group.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneTaskScreen(
    state: TaskUiState,
    status: ConnectionStatus,
    nowSeconds: Long,
    rowOf: (Long) -> SessionRow?,
    handlers: PhoneTaskHandlers = PhoneTaskHandlers(),
    modifier: Modifier = Modifier,
) {
    val o = Fleet.colors
    val h = handlers.task
    val task = state.task
    val connection = rememberPhoneConnection(status)
    var pastOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = task?.label ?: "Task",
            subtitle = task?.title?.takeIf { it.isNotBlank() && task.key != null },
            titleStyle = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            navigation = { IconButton(onClick = h.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = h.onRefresh, enabled = !state.loading) { Icon(FleetIcons.Refresh, contentDescription = "Refresh") }
            },
        )
        HubBanner(connection, asOf = state.stale?.substringAfter("as of ", "")?.takeIf { it.isNotBlank() }, onRetry = h.onRefresh)
        ErrorBanner(state.error, onDismiss = h.onDismissError)
        if (state.error != null && state.conflict) ReloadRow(h.onRefresh)
        if ((state.loading || state.busy) && rememberLoaderVisible(true)) DotWave(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        if (state.gone) {
            Text(TASK_NOT_VISIBLE, color = o.fgMuted, fontSize = 15.sp, modifier = Modifier.padding(24.dp))
            return@Column
        }
        if (task == null) return@Column
        val gutter = OrbitTokens.spacing("phone-gutter").dp
        val primary = state.active.firstOrNull { it.primary && it.sessionId != null }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            item(key = "facts") {
                Column(modifier = Modifier.padding(horizontal = gutter, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val statusText = listOfNotNull(task.statusName, task.resolution).filter { it.isNotBlank() }.joinToString(" · ")
                            .ifBlank { if (task.kind == TaskKind.Local) "Local work" else if (task.kind == TaskKind.Ref) "No ticket" else "" }
                        if (statusText.isNotBlank()) OrbitChip(statusText)
                        state.orgName?.let { name ->
                            Row(
                                modifier = Modifier.background(o.chipBg, RoundedCornerShape(OrbitTokens.radius("radius-sm").dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OrgSwatch(state.orgColor, size = 8)
                                Text(name, color = o.fg2, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                        }
                        val g = task.group
                        if (g.source != GroupSource.None && g.title.isNotBlank()) {
                            OrbitChip(listOfNotNull(g.title, groupChipWords(g.source)).joinToString(" · "))
                        }
                        for (a in task.assignees.take(2)) OrbitChip(a)
                        task.dueAt?.takeIf { it.isNotBlank() }?.let { OrbitChip("Due $it") }
                        task.trackerName?.takeIf { it.isNotBlank() }?.let { OrbitChip(it) }
                    }
                    if (task.unavailable) Text("The tracker no longer answers for this ticket.", color = o.statusFailed, fontSize = 13.sp)
                    if (task.trackerDown) Text("Its tracker is failing to sync. The sessions below are what fleet last knew.", color = o.fgMuted, fontSize = 13.sp)
                    blockedLine(task, { null })?.let { Text("$it.", color = o.statusWaiting, fontSize = 13.sp) }
                    if (state.trackerControlled) {
                        Text("The tracker decides this group; placing it changes only fleet's view.", color = o.fgMuted, fontSize = 13.sp)
                    }
                    state.detail?.placement?.note?.takeIf { it.isNotBlank() }?.let { Text("Note: $it", color = o.fgMuted, fontSize = 13.sp) }
                    state.detail?.description?.takeIf { it.isNotBlank() }?.let { MarkdownText(it) }
                    state.detail?.notes?.takeIf { it.isNotBlank() && task.editable }?.let { MarkdownText(it) }
                    OwnTaskControls(state, h)
                    task.url?.takeIf { it.isNotBlank() }?.let { url ->
                        val uri = LocalUriHandler.current
                        Text(
                            "Open in ${task.trackerName?.takeIf { it.isNotBlank() } ?: "the tracker"}",
                            color = o.accent,
                            fontSize = 14.sp,
                            modifier = Modifier.heightIn(min = 32.dp).clickable { runCatching { uri.openUri(url) } }.padding(vertical = 6.dp),
                        )
                    }
                }
                HorizontalDivider(color = o.border)
            }
            val sessions = state.active.size + state.suggested.size
            if (sessions > 0) {
                item(key = "h-sessions") { SectionTitle("Sessions", sessions) }
            }
            items(state.active, key = { "a-${it.linkId}" }) { link ->
                val row = link.sessionId?.let(rowOf)
                val word = linkWord(link, row)
                PhoneRow(
                    title = (if (link.primary) "★ " else "") + (row?.displayName ?: link.name.ifBlank { "Session" }),
                    line = listOfNotNull(row?.let(::waitsOn)?.takeIf { it.isNotBlank() }, link.host.takeIf { it.isNotBlank() }).joinToString(" · "),
                    word = word,
                    lead = phoneLead(word, status.isConnected()),
                    age = relativeTime(row?.lastActivityAt, nowSeconds),
                    onClick = if (link.sessionId != null) ({ h.onOpenSession(link) }) else null,
                    chips = prChipOf(link.prUrl),
                )
            }
            items(state.suggested, key = { "s-${it.linkId}" }) { link ->
                SuggestedLink(link, link.sessionId?.let(rowOf), state, handlers, nowSeconds)
            }
            if (state.past.isNotEmpty()) {
                item(key = "past-fold") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = OrbitTokens.spacing("touch-min").dp)
                            .clickable { pastOpen = !pastOpen }
                            .padding(horizontal = gutter),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(pastFoldLine(state.past), color = o.fgMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Fold(!pastOpen)
                    }
                }
                if (pastOpen) {
                    items(state.past, key = { "p-${it.linkId}" }) { link -> PastCard(link, state, handlers, nowSeconds) }
                }
            }
            if (sessions == 0 && state.past.isEmpty()) {
                item(key = "none") { Text("No session has worked on this yet.", color = o.fgMuted, fontSize = 15.sp, modifier = Modifier.padding(gutter)) }
            }
            if (task.sessionsMore > 0) {
                item(key = "more") { Text("…and ${task.sessionsMore} more on the desktop.", color = o.fgMuted, fontSize = 13.sp, modifier = Modifier.padding(gutter)) }
            }
        }
        // The actions, last and in thumb reach.
        HorizontalDivider(color = o.border)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val enabled = !state.busy
            if (primary != null) {
                Button(
                    onClick = { h.onOpenSession(primary) },
                    colors = ButtonDefaults.buttonColors(containerColor = o.accent, contentColor = o.accentFg),
                ) { Text("Open ${primary.name.ifBlank { "session" }}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (state.canContinue) {
                Button(
                    onClick = h.onContinue,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = o.accent, contentColor = o.accentFg),
                ) { Text("Continue") }
            }
            if (state.canStart) OutlinedButton(onClick = h.onStartHere, enabled = enabled) { Text("Start new here") }
            if (state.canPlace) TextButton(onClick = h.onOpenPlace, enabled = enabled) { Text("Place in group…", color = o.accent) }
            if (state.canClearPlacement) TextButton(onClick = h.onClearPlacement, enabled = enabled) { Text("Back to the rule's group", color = o.accent) }
        }
    }
    if (state.placeOpen) PlaceSheet(state, h)
    if (state.editOpen) TaskEditSheetFor(state, h)
}

@Composable
private fun SectionTitle(title: String, count: Int? = null) {
    val o = Fleet.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = OrbitTokens.spacing("phone-gutter").dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        if (count != null) Text("$count", color = o.fgMuted, fontSize = 15.sp)
    }
}

/** A suggested session: its word, the reason it was suggested (by a rule or a heuristic, so "Suggested"), and Link / Not this. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestedLink(link: WorkTaskLink, row: SessionRow?, state: TaskUiState, handlers: PhoneTaskHandlers, nowSeconds: Long) {
    val o = Fleet.colors
    val word = linkWord(link, row)
    Column {
        PhoneRow(
            title = row?.displayName ?: link.name.ifBlank { "Session" },
            line = listOfNotNull(link.host.takeIf { it.isNotBlank() }, link.prUrl?.takeIf { it.isNotBlank() }?.let(::prLabel)).joinToString(" · "),
            word = word,
            lead = word?.label,
            separator = " · ",
            age = relativeTime(row?.lastActivityAt, nowSeconds),
            divider = false,
            onClick = if (link.sessionId != null) ({ handlers.task.onOpenSession(link) }) else null,
        )
        Column(
            modifier = Modifier.padding(start = 38.dp, end = OrbitTokens.spacing("phone-gutter").dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                listOfNotNull("Suggested", link.rule?.takeIf { it.isNotBlank() }?.let { "by rule “$it”" }, link.why?.takeIf { it.isNotBlank() }).joinToString(" · "),
                color = o.fgMuted,
                fontSize = 13.sp,
            )
            if (link.crossOrg) Text("⚠ This session belongs to another organisation.", color = o.statusWaiting, fontSize = 13.sp)
            if (state.canDecide && link.sessionId != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { handlers.onLink(link) }, enabled = !state.busy) { Text(if (link.crossOrg) "Link anyway" else "Link") }
                    TextButton(onClick = { handlers.onNotThis(link) }, enabled = !state.busy) { Text("Not this", color = o.fg2) }
                }
            }
        }
        HorizontalDivider(color = o.border)
    }
}

/** A past session: how it ended, where and when, its PR, and its summary inline once written. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PastCard(link: WorkTaskLink, state: TaskUiState, handlers: PhoneTaskHandlers, nowSeconds: Long) {
    val o = Fleet.colors
    val h = handlers.task
    val word = linkWord(link, null)
    val summary = state.summaries[link.linkId]
    val summarizing = state.summarizing == link.linkId
    val uri = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = 6.dp)
            .border(1.dp, o.border, RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                link.name.ifBlank { "Session" },
                color = o.fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (link.state == LinkState.Rejected) TextDecoration.LineThrough else null,
            )
            if (link.state == LinkState.Rejected) OrbitChip("Not this") else OrbitChip(pastLead(link), word = word)
        }
        Text(
            listOfNotNull(
                link.host.takeIf { it.isNotBlank() },
                link.branch?.takeIf { it.isNotBlank() },
                relativeTime(link.endedAt ?: link.decidedAt, nowSeconds)?.let { if (it == "just now") it else "$it ago" },
            ).joinToString(" · "),
            color = o.fgMuted,
            fontSize = 13.sp,
        )
        when {
            summary != null -> {
                Text(summary.summary, color = o.fg2, fontSize = 14.sp, lineHeight = 20.sp)
                FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("✎ ${draftedLine(summary)}", color = o.fgMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    if (state.canSummarize) {
                        TextButton(onClick = { h.onSummarize(link) }, enabled = state.summarizing == null) { Text("Regenerate", color = o.accent, fontSize = 13.sp) }
                    }
                    TextButton(onClick = { handlers.onClearSummary(link.linkId) }) { Text("Clear", color = o.accent, fontSize = 13.sp) }
                }
            }
            summarizing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rememberLoaderVisible(true)) DotWave()
                Text("Summarizing", color = o.fgMuted, fontSize = 13.sp)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (summary == null && !summarizing && link.state == LinkState.Ended && state.canSummarize) {
                TextButton(onClick = { h.onSummarize(link) }, enabled = state.summarizing == null) { Text("Summarize", color = o.accent) }
            }
            if (link.sessionId != null) TextButton(onClick = { h.onOpenSession(link) }) { Text("Open session", color = o.accent) }
            link.prUrl?.takeIf { it.isNotBlank() }?.let { url ->
                TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(prLabel(url), color = o.accent) }
            }
        }
    }
}

// ── To review ──

/**
 * **To review** on the New bar: the same cards as Classic's Review sheet,
 * with the count the chip shows. A suggestion says why and offers Link, Not
 * this and Other ticket…; a link across organisations is a warning card
 * (Link anyway, Not this) and never part of the bulk action, which sits last
 * and names how many ordinary suggestions it links. Each card says what its
 * session is doing.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PhoneReviewSheet(state: ReviewUiState, handlers: ReviewHandlers, rowOf: (Long) -> SessionRow?) {
    val o = Fleet.colors
    BottomSheet(
        title = "To review" + if (state.total > 0) " · ${state.total}" else "",
        onDismiss = handlers.onClose,
        cancelLabel = "Close",
    ) {
        Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!state.connected) {
                Text(
                    (state.stale ?: "Offline") + ". Nothing can be decided until the hub is back; nothing is queued.",
                    color = o.fgMuted,
                    fontSize = 13.sp,
                )
            }
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (state.error != null && state.conflict) ReloadRow(handlers.onReload)
            state.undo?.let { undo ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(o.bgRaise, RoundedCornerShape(OrbitTokens.radius("radius-md").dp)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(undo.label, color = o.fg, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.canUndo) TextButton(onClick = handlers.onUndo, enabled = !state.busy) { Text("Undo", color = o.accent) }
                    TextButton(onClick = handlers.onDismissUndo) { Text("OK", color = o.fg2) }
                }
            }
            if ((state.loading || state.busy) && rememberLoaderVisible(true)) DotWave()
            if (state.loaded && state.items.isEmpty()) Text("Nothing to review.", color = o.fgMuted, fontSize = 15.sp)
            for (item in state.items) PhoneReviewCard(item, state, handlers, item.sessionId.let(rowOf))
            if (state.hasMore) {
                TextButton(onClick = handlers.onLoadMore, enabled = state.connected && !state.loadingMore) {
                    Text(if (state.loadingMore) "Loading…" else "Show more (${state.items.size} of ${state.total})", color = o.accent)
                }
            }
            // Last, after every card has been read: never above the first one.
            if (state.canBatch && state.batchCount > 0 && state.items.size > 1) {
                OutlinedButton(onClick = handlers.onConfirmAll, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(linkAllLabel(state.batchCount))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneReviewCard(item: ReviewItem, state: ReviewUiState, handlers: ReviewHandlers, row: SessionRow?) {
    val o = Fleet.colors
    val warn = item.kind == ReviewKind.CrossOrg
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)
    val word = row?.let(::phoneWord)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (warn) Modifier.background(o.waitingFaint, shape).border(1.dp, o.waitingLine, shape) else Modifier.border(1.dp, o.border, shape))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (warn) "⚠ Crosses organisations" else reviewKindWords(item.kind) + (item.rule?.takeIf { it.isNotBlank() }?.let { " · by rule “$it”" } ?: ""),
            color = if (warn) o.statusWaiting else o.fgMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            item.task.label + (item.task.title.takeIf { it.isNotBlank() && item.task.key != null }?.let { " · $it" } ?: ""),
            color = o.fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildList {
                add("↔ " + (row?.displayName ?: item.sessionName.ifBlank { "session ${item.sessionId}" }))
                item.host.takeIf { it.isNotBlank() }?.let { add(it) }
                word?.let { add(it.label) }
            }.joinToString(" · "),
            color = o.fg2,
            fontSize = 14.sp,
        )
        if (item.why.isNotEmpty()) Text(item.why.joinToString(" · "), color = o.fgMuted, fontSize = 13.sp)
        state.failures[item.linkId]?.let { Text(it.message, color = o.statusFailed, fontSize = 13.sp) }
        val enabled = !state.busy
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (item.kind) {
                ReviewKind.Suggestion -> {
                    if (state.canConfirm) OutlinedButton(onClick = { handlers.onConfirm(item) }, enabled = enabled) { Text("Link") }
                    if (state.canReject) TextButton(onClick = { handlers.onReject(item) }, enabled = enabled) { Text("Not this", color = o.fg2) }
                    if (state.canChange && item.alternatives.isNotEmpty()) {
                        TextButton(onClick = { handlers.onToggleChange(item) }, enabled = enabled) { Text("Other ticket…", color = o.accent) }
                    }
                }
                ReviewKind.CrossOrg -> {
                    if (state.canKeep) OutlinedButton(onClick = { handlers.onKeep(item) }, enabled = enabled) { Text("Link anyway") }
                    if (state.canRemove) TextButton(onClick = { handlers.onRemove(item) }, enabled = enabled) { Text("Not this", color = o.fg2) }
                }
                ReviewKind.Unavailable -> {
                    if (state.canKeep) OutlinedButton(onClick = { handlers.onKeep(item) }, enabled = enabled) { Text("Keep") }
                    if (state.canRemove) TextButton(onClick = { handlers.onRemove(item) }, enabled = enabled) { Text("Remove", color = o.fg2) }
                }
                ReviewKind.NoPrimary -> {
                    if (state.canMakePrimary) OutlinedButton(onClick = { handlers.onMakePrimary(item) }, enabled = enabled) { Text("Make primary") }
                }
                ReviewKind.Unknown -> Unit
            }
        }
        if (warn) Text("Never part of Link all.", color = o.fgMuted, fontSize = 12.sp)
        if (state.changing == item.linkId) {
            Text("Link it to instead:", color = o.fgMuted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (alt in item.alternatives) {
                    OutlinedButton(onClick = { handlers.onChange(item, alt) }, enabled = enabled) {
                        Text(alt.label + (alt.title.takeIf { it.isNotBlank() && alt.key != null }?.let { " · $it" } ?: ""), maxLines = 1)
                    }
                }
            }
        }
    }
}
