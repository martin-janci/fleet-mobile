package dev.claudefleet.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.GrantLevel
import dev.claudefleet.mobile.model.MyAccess
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.model.limitAt
import dev.claudefleet.mobile.model.askedAt
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.help.TourAnchor
import dev.claudefleet.mobile.ui.help.TourAnchors
import dev.claudefleet.mobile.ui.help.tourAnchor
import dev.claudefleet.mobile.ui.kit.OrbitPullToRefresh
import dev.claudefleet.mobile.ui.kit.PhoneConnection
import dev.claudefleet.mobile.ui.kit.StatusWord
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.StatusTone
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedButton
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.missionWaitLabel
import dev.claudefleet.mobile.model.loginLabel
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.model.relativeWithin
import dev.claudefleet.mobile.model.usedText
import dev.claudefleet.mobile.ui.kit.OrbitChip
import dev.claudefleet.mobile.ui.kit.PhoneRow

/**
 * What needs a person, fleet-wide, oldest ask first: the New layout's first
 * tab. Not the Sessions list's *Needs you* filter, which follows that list's
 * other filters; the Inbox is every session that needs you, whatever the
 * Sessions tab is narrowed to, and its count is the bar's one badge.
 *
 * Sorted by when each one asked ([askedAt]: the hub's attention stamp, else
 * the nearest the phone has), so the one waiting longest is on top, then by
 * id so the list does not reshuffle on every event frame.
 */
fun inboxRows(sessions: List<SessionRow>, access: MyAccess = MyAccess.UNKNOWN): List<SessionRow> =
    sessions.filter { it.needsAttention && access.levelFor(it) == null }
        .sortedWith(compareBy<SessionRow, Long?>(nullsLast()) { it.askedAt }.thenBy { it.id })

/**
 * Jev's "probably waiting" rows (hub contract 15, gap plan G1.6): a
 * proposal, kept apart from [inboxRows] and never in its count — the Inbox
 * header says "+N proposed" instead. The desktop's `proposedRows`, oldest
 * first like the Inbox.
 */
fun proposedRows(
    sessions: List<SessionRow>,
    access: MyAccess = MyAccess.UNKNOWN,
    /** Readings set aside with Not waiting on this phone ([proposalKey]), left out (G5.4). */
    setAside: Set<String> = emptySet(),
): List<SessionRow> =
    sessions.filter { it.isProposed && access.levelFor(it) == null && proposalKey(it) !in setAside }
        .sortedWith(compareBy<SessionRow, Long?>(nullsLast()) { it.askedAt }.thenBy { it.id })

/** One session someone else shared with this person, and at what level (redesign 11.10, the Watch board). */
data class SharedRow(val row: SessionRow, val level: String)

/**
 * The Inbox's *Shared with me* section: every session shared with this
 * person, most recently active first. Its questions are its owner's, so it
 * sits here rather than under *Needs you*; [inboxRows] leaves it out.
 */
fun sharedRows(sessions: List<SessionRow>, access: MyAccess): List<SharedRow> =
    sessions.mapNotNull { row -> access.levelFor(row)?.let { SharedRow(row, it) } }
        .sortedWith(compareByDescending<SharedRow, Long?>(nullsFirst()) { it.row.lastActivityAt }.thenBy { it.row.id })

/** "Answer · waiting for its owner": a shared row's line under its name. */
internal fun sharedLine(s: SharedRow): String =
    GrantLevel.word(s.level) + if (s.row.needsAttention) " · waiting for its owner" else ""

/**
 * The word a needs-you row leads with: Paused for an account at its limit
 * (it waits on a reset or a switch, not an answer), Failed for stuck and
 * failed, else Needs you.
 */
internal fun inboxWord(row: SessionRow): StatusWord =
    when (StatusWord.of(StatusTone.of(row))) {
        StatusWord.PAUSED -> StatusWord.PAUSED
        StatusWord.FAILED -> StatusWord.FAILED
        else -> StatusWord.NEEDS_YOU
    }

/**
 * The Inbox's three views (boards MobileNav, MobileTutorials): Needs you,
 * Working, Done today. The boards say Running; the manual's six status words
 * say Working (review r14 CopyRulesTest).
 */
enum class InboxView(val label: String) { NeedsYou("Needs you"), Running("Working"), DoneToday("Done today") }

/** Running: the sessions working now, most recently active first. */
fun runningRows(sessions: List<SessionRow>): List<SessionRow> =
    sessions.filter { it.claudeStatus == "working" && it.stuckKind.isNullOrBlank() }
        .sortedWith(compareByDescending<SessionRow, Long?>(nullsLast()) { it.lastActivityAt }.thenBy { it.id })

/**
 * Done today: the sessions whose turn finished since [midnight] (local), the
 * newest first. A turn that ended with a question or a failure is under
 * Needs you instead; a working session is Running.
 */
fun doneTodayRows(sessions: List<SessionRow>, midnight: Long): List<SessionRow> =
    sessions.filter { r ->
        !r.needsAttention && r.stuckKind.isNullOrBlank() && (r.lastActivityAt ?: 0) >= midnight &&
            StatusWord.of(StatusTone.of(r.claudeStatus, r.stuckKind)).let { it == StatusWord.DONE || it == StatusWord.IDLE }
    }.sortedWith(compareByDescending<SessionRow, Long?>(nullsLast()) { it.lastActivityAt }.thenBy { it.id })

/** "4 need you · 6 running · +1 proposed": the Inbox header's line; Jev's proposals ([proposedRows]) never join the count. */
internal fun inboxSubtitle(needYou: Int, running: Int, proposed: Int = 0): String =
    "$needYou need${if (needYou == 1) "s" else ""} you · $running running" + if (proposed > 0) " · +$proposed proposed" else ""

/**
 * What an empty Inbox says. "Nothing needs you" is a claim about the fleet,
 * and only a live hub can make it: while connecting or offline an empty list
 * may just be a list the phone has not been told about yet.
 */
internal fun inboxEmptyText(connection: PhoneConnection): String = when (connection) {
    PhoneConnection.Live -> "Nothing needs you."
    is PhoneConnection.Reconnecting -> "Connecting to the hub. Anything that needs you appears once it answers."
    is PhoneConnection.Offline -> "Not connected. This phone can't tell whether anything needs you until the hub answers."
    is PhoneConnection.Refused -> "Not connected. This hub can't be used from this phone."
}

@Composable
fun InboxScreen(
    rows: List<SessionRow>,
    running: Int,
    nowSeconds: Long,
    onOpenSession: (Long) -> Unit,
    onOpenToday: (() -> Unit)?,
    modifier: Modifier = Modifier,
    live: Boolean = true,
    /** Where the phone stands with the hub: what an empty Inbox may claim. */
    connection: PhoneConnection = PhoneConnection.Live,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    /** Above the list: the hub-version banner and the Update ready line (14.18), when there are any. */
    top: @Composable () -> Unit = {},
    /** Where the tour's stops are (14.22); null records nothing. */
    anchors: TourAnchors? = null,
    /** Account uuid → label, for a row's account and a paused row's line (step 4.10). */
    accountNames: Map<String, String> = emptyMap(),
    /** Account uuid → usage reading, for when a paused row's limit resets (step 4.10). */
    accountUsage: Map<String, AccountUsageSnapshot> = emptyMap(),
    /** Sessions shared with this person (redesign 11.10), drawn under *Needs you*. */
    shared: List<SharedRow> = emptyList(),
    /** The Running view's rows ([runningRows]); empty leaves the view switch out. */
    runningList: List<SessionRow> = emptyList(),
    /** The Done today view's rows ([doneTodayRows]). */
    doneTodayList: List<SessionRow> = emptyList(),
    /** Jev's "probably waiting" rows ([proposedRows]): named in the header, never counted. */
    proposed: Int = 0,
    /** Those rows themselves (G5.4), drawn under Needs you with Not waiting. */
    proposedList: List<SessionRow> = emptyList(),
    /** Missions that wait on a person ([missionWaits]), rows of their own under the sessions (G5.4). */
    missionRows: List<Mission> = emptyList(),
    onOpenMission: (Long) -> Unit = {},
    /** The rows' inline answers (G5.4): what each row offers, and what was pressed. */
    inbox: InboxUiState = InboxUiState(),
    rowActions: (SessionRow) -> InboxRowActions = { InboxRowActions() },
    rowHandlers: InboxRowHandlers = InboxRowHandlers(),
) {
    var view by rememberSaveable { mutableStateOf(InboxView.NeedsYou) }
    // A mission waiting on a person is one more thing that needs you (G5.4).
    val needYou = rows.size + missionRows.size
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Inbox",
            subtitle = inboxSubtitle(needYou, running, proposed),
            modifier = Modifier.tourAnchor(anchors, TourAnchor.Header),
            actions = {
                onOpenToday?.let { TextButton(onClick = it, modifier = Modifier.tourAnchor(anchors, TourAnchor.Today)) { Text("Today") } }
            },
        )
        top()
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            for (v in InboxView.entries) {
                val n = when (v) {
                    InboxView.NeedsYou -> needYou
                    InboxView.Running -> runningList.size
                    InboxView.DoneToday -> doneTodayList.size
                }
                FilterChip(selected = v == view, onClick = { view = v }, label = { Text("${v.label} $n") })
            }
        }
        if (view != InboxView.NeedsYou) {
            val list = if (view == InboxView.Running) runningList else doneTodayList
            OrbitPullToRefresh(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (list.isEmpty()) {
                        item(key = "empty-${view.name}") {
                            Box(modifier = Modifier.fillParentMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    if (view == InboxView.Running) "Nothing is running." else "Nothing finished today yet.",
                                    color = Fleet.colors.fgMuted,
                                    fontSize = 15.sp,
                                )
                            }
                        }
                    }
                    items(list, key = { "${view.name}-${it.id}" }) { row ->
                        PhoneSessionRow(
                            row = row,
                            nowSeconds = nowSeconds,
                            live = live,
                            showHost = true,
                            accountName = row.accountUuid?.let(accountNames::get),
                            onClick = { onOpenSession(row.id) },
                        )
                    }
                }
            }
        } else OrbitPullToRefresh(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (rows.isEmpty() && shared.isEmpty() && missionRows.isEmpty() && proposedList.isEmpty()) {
                    item(key = "empty") {
                        Box(modifier = Modifier.fillParentMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(inboxEmptyText(connection), color = Fleet.colors.fgMuted, fontSize = 15.sp, textAlign = TextAlign.Center)
                        }
                    }
                } else if (needYou > 0) {
                    item(key = "heading") {
                        Text(
                            "Needs you · oldest first",
                            color = Fleet.colors.fgMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                items(rows, key = { it.id }) { row ->
                    Column {
                        PhoneSessionRow(
                            modifier = if (row.id == rows.first().id) Modifier.tourAnchor(anchors, TourAnchor.FirstRow) else Modifier,
                            row = row,
                            nowSeconds = nowSeconds,
                            live = live,
                            showHost = true,
                            since = row.askedAt,
                            accountName = row.accountUuid?.let(accountNames::get),
                            limit = row.accountUuid?.let(accountUsage::get)?.limitAt(nowSeconds),
                            onClick = { onOpenSession(row.id) },
                        )
                        InboxRowButtons(
                            row = row,
                            a = rowActions(row),
                            inbox = inbox,
                            nowSeconds = nowSeconds,
                            handlers = rowHandlers,
                            onOpenLog = { onOpenSession(row.id) },
                            accountName = { accountNames[it] },
                        )
                    }
                }
                items(missionRows, key = { "mission-${it.id}" }) { m ->
                    PhoneRow(
                        title = m.name.ifBlank { "Mission ${m.id}" },
                        line = m.waitingOn?.let(::missionWaitLabel).orEmpty(),
                        word = StatusWord.NEEDS_YOU,
                        lead = phoneLead(StatusWord.NEEDS_YOU, live),
                        age = relativeTime(m.waitingOn?.since?.takeIf { it > 0 }, nowSeconds),
                        onClick = { onOpenMission(m.id) },
                        chips = { OrbitChip("Mission") },
                    )
                }
                if (proposedList.isNotEmpty()) {
                    item(key = "proposed-heading") {
                        Text(
                            "Proposed by Jev · not counted",
                            color = Fleet.colors.fgMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(proposedList, key = { "proposed-${it.id}" }) { row ->
                        Column {
                            PhoneSessionRow(
                                row = row,
                                nowSeconds = nowSeconds,
                                live = live,
                                showHost = true,
                                accountName = row.accountUuid?.let(accountNames::get),
                                onClick = { onOpenSession(row.id) },
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                            ) {
                                Text(
                                    "Proposed by Jev · $PROPOSED_LINE",
                                    color = Fleet.colors.fg2,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { rowHandlers.onNotWaiting(row) }) { Text("Not waiting") }
                            }
                        }
                    }
                }
                if (shared.isNotEmpty()) {
                    item(key = "shared-heading") {
                        Text(
                            "Shared with me · ${shared.size}",
                            color = Fleet.colors.fgMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(shared, key = { "shared-${it.row.id}" }) { s ->
                        Column {
                            PhoneSessionRow(
                                row = s.row,
                                nowSeconds = nowSeconds,
                                live = live,
                                showHost = true,
                                accountName = s.row.accountUuid?.let(accountNames::get),
                                onClick = { onOpenSession(s.row.id) },
                            )
                            Text(
                                sharedLine(s),
                                color = Fleet.colors.fg2,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What the Inbox's inline answers call (G5.4); the defaults do nothing. */
class InboxRowHandlers(
    val onRetry: (SessionRow) -> Unit = {},
    val onProposeSwitch: (SessionRow) -> Unit = {},
    val onConfirmSwitch: (SessionRow) -> Unit = {},
    val onCancelSwitch: (Long) -> Unit = {},
    val onWait: (Long, Long) -> Unit = { _, _ -> },
    val onNotWaiting: (SessionRow) -> Unit = {},
)

/**
 * A Needs you row's fix, under it (board MobileNav): Open log and Retry on a
 * failed row; Switch account and Wait on one paused at its account's limit.
 * Switch account asks first and moves only on Switch; Wait folds the buttons
 * into "Waiting for the reset". Neither is pre-selected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxRowButtons(
    row: SessionRow,
    a: InboxRowActions,
    inbox: InboxUiState,
    nowSeconds: Long,
    handlers: InboxRowHandlers,
    onOpenLog: () -> Unit,
    accountName: (String) -> String?,
) {
    val notice = inbox.notices[row.id]
    val target = inbox.switchTargets[row.id]
    val waiting = inbox.waiting[row.id]?.takeIf { it > nowSeconds }
    if (!a.any && notice == null) return
    val busy = row.id in inbox.busy
    val o = Fleet.colors
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        when {
            target != null -> {
                Text("Resume under ${loginLabel(target, accountName)} · ${usedText(target)}?", color = o.fg2, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { handlers.onConfirmSwitch(row) }, enabled = !busy) { Text("Switch") }
                    TextButton(onClick = { handlers.onCancelSwitch(row.id) }, enabled = !busy) { Text("Cancel") }
                }
            }
            waiting != null -> Text(
                relativeWithin(waiting, nowSeconds)?.let { "Waiting for the reset · in $it" } ?: "Waiting for the reset",
                color = o.fg2,
                fontSize = 13.sp,
            )
            a.any -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (a.openLog) TextButton(onClick = onOpenLog) { Text("Open log") }
                if (a.retryPrompt != null) OutlinedButton(onClick = { handlers.onRetry(row) }, enabled = !busy) { Text("Retry") }
                if (a.switchAccount) OutlinedButton(onClick = { handlers.onProposeSwitch(row) }, enabled = !busy) { Text("Switch account") }
                a.waitUntil?.let { resets ->
                    waitLabel(resets, nowSeconds)?.let { label ->
                        TextButton(onClick = { handlers.onWait(row.id, resets) }, enabled = !busy) { Text(label) }
                    }
                }
            }
        }
        notice?.let { Text(it, color = o.fg2, fontSize = 12.sp) }
    }
}
