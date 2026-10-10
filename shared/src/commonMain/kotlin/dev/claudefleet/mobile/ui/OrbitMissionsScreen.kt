package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.dollars
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.PhoneRow
import dev.claudefleet.mobile.ui.kit.SheetAction
import dev.claudefleet.mobile.ui.kit.StatusWord
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import dev.claudefleet.mobile.ui.kit.ListBody
import dev.claudefleet.mobile.ui.kit.LoadFailed
import dev.claudefleet.mobile.ui.kit.listBody
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/*
 * Missions as a screen in the New layout (redesign 14.16, board
 * MobileMissions): what runs, what is paused, what finished this week, and
 * Pause all, which says exactly what stops before it stops anything. A
 * mission opens in the Missions sheet's detail, where its steps, cards and
 * autonomy already are ("mission detail stays on MobileControl").
 *
 * The spend ask (Approve and Deny, neither pre-selected) is the mission
 * detail's first card ([SpendAskCard]), derived from the budget brake's ask
 * card and the grant ([spendAsk]). The missions that wait on a person
 * (contract 15's `waiting_on`) come first, under "Waits on you" (gap plan
 * G5.7); each row says its autonomy and draws its spend against its budget.
 */

/** A week, for "Done this week". */
private const val WEEK = 7 * 86_400L

/** The list's groups, each newest first. */
data class MissionGroups(
    /** Active missions that wait on a person, the longest waiting first ([missionWaits]). */
    val waiting: List<Mission> = emptyList(),
    val running: List<Mission>,
    val paused: List<Mission>,
    val drafts: List<Mission>,
    val doneThisWeek: List<Mission>,
) {
    val isEmpty: Boolean get() = waiting.isEmpty() && running.isEmpty() && paused.isEmpty() && drafts.isEmpty() && doneThisWeek.isEmpty()
}

internal fun missionGroups(missions: List<Mission>, nowSeconds: Long): MissionGroups {
    val newest = missions.sortedByDescending { it.updatedAt }
    val waiting = missionWaits(missions)
    val waitingIds = waiting.map { it.id }.toSet()
    return MissionGroups(
        waiting = waiting,
        running = newest.filter { it.state == "active" && it.id !in waitingIds },
        paused = newest.filter { it.state == "paused" },
        drafts = newest.filter { it.state == "draft" },
        doneThisWeek = newest.filter { it.state in FINISHED && nowSeconds - it.updatedAt < WEEK },
    )
}

private val FINISHED = setOf("completed", "failed", "cancelled")

/** "3 running · 1 paused", "No missions running". */
internal fun missionsHeadline(g: MissionGroups): String = buildList {
    // A waiting mission is still running: it is counted in both, as the board says "3 running · 1 waits on you".
    val active = g.running.size + g.waiting.size
    if (active > 0) add("$active running")
    if (g.waiting.isNotEmpty()) add("${g.waiting.size} ${if (g.waiting.size == 1) "waits" else "wait"} on you")
    if (g.paused.isNotEmpty()) add("${g.paused.size} paused")
    if (g.drafts.isNotEmpty()) add("${g.drafts.size} ${if (g.drafts.size == 1) "draft" else "drafts"}")
}.joinToString(" · ").ifEmpty { "No missions running" }

/** The step a mission is on: the first not done, as the board counts it ("step 2 of 5"). */
internal fun missionStep(m: Mission): String? =
    if (m.total <= 0) null else "step ${(m.done + 1).coerceAtMost(m.total)} of ${m.total}"

/** A row's status word: the colour and the word together, never the colour alone. */
internal fun missionWord(m: Mission): StatusWord? = when (m.state) {
    "active" -> StatusWord.WORKING
    "paused" -> StatusWord.PAUSED
    "completed" -> StatusWord.DONE
    "failed" -> StatusWord.FAILED
    else -> null
}

/** A row's second line, after its word. */
internal fun missionLine(m: Mission): String = when (m.state) {
    "active", "paused" -> missionStep(m) ?: "no steps yet"
    "completed" -> if (m.total > 0) "${m.done} of ${m.total} steps" else "finished"
    "failed" -> missionStep(m)?.let { "at $it" } ?: "stopped"
    "cancelled" -> "Cancelled"
    "draft" -> "Draft · plan it in Control"
    else -> m.state
}

/**
 * What a mission spent, against its live grant's budget when one sets it
 * (contract 14): "$1.20 of $5.00", or "$1.20 spent". Null from an older hub
 * and for a mission that has spent nothing and has no budget.
 */
internal fun missionSpend(m: Mission): String? {
    val cost = m.costMicros ?: return null
    m.budgetMicros?.let { return "${dollars(cost)} of ${dollars(it)}" }
    return if (cost > 0) "${dollars(cost)} spent" else null
}

/**
 * The autonomy a mission asked for, short, for its row (board MobileMissions:
 * "autonomy: ask before push"). Null at level 0, which keeps the cards only
 * and so says nothing a row needs.
 */
internal fun missionAutonomy(m: Mission): String? = when {
    m.level <= 0 -> null
    m.level == 1 -> "autonomy: ask before each step"
    m.level == 2 -> "autonomy: runs ready work"
    else -> "autonomy: runs and closes work"
}

/** A row's second line with its spend and its autonomy. */
internal fun missionRowLine(m: Mission): String =
    listOfNotNull(missionLine(m), missionSpend(m), missionAutonomy(m)).joinToString(" · ")

/** How much of its budget a mission spent, 0 to 1, for the row's meter; null with no budget. */
internal fun missionSpendFraction(m: Mission): Float? {
    val budget = m.budgetMicros?.takeIf { it > 0 } ?: return null
    return ((m.costMicros ?: 0).toDouble() / budget).toFloat().coerceIn(0f, 1f)
}

/** What Pause all says it stops, before it stops it. */
internal fun pauseAllMeta(running: Int): String =
    "${if (running == 1) "1 mission stops" else "$running missions stop"} starting new steps. " +
        "Sessions already working finish their current turn, then wait."

internal fun pauseAllLabel(running: Int): String = if (running == 1) "Pause 1 mission" else "Pause $running missions"

data class OrbitMissionsHandlers(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    /** Pause every running mission; null where this pairing may not. */
    val onPauseAll: (() -> Unit)? = null,
    /** A new mission is planned with Control; null where the hub offers no coordinator. */
    val onNewInControl: (() -> Unit)? = null,
    val onDismissError: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitMissionsScreen(
    state: MissionsUiState,
    nowSeconds: Long,
    handlers: OrbitMissionsHandlers,
    modifier: Modifier = Modifier,
) {
    val o = Fleet.colors
    val groups = missionGroups(state.missions, nowSeconds)
    var askingPause by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize().background(o.bg)) {
        ScreenHeader(
            title = "Missions",
            subtitle = if (state.missions.isEmpty() && state.loading) null else missionsHeadline(groups),
            navigation = {
                IconButton(onClick = handlers.onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") }
            },
        )
        ErrorBanner(state.error, onDismiss = handlers.onDismissError, onRetry = handlers.onRefresh.takeIf { state.listFailed })
        state.notice?.let { Text(it, color = o.fg2, fontSize = 14.sp, modifier = Modifier.padding(horizontal = gutter(), vertical = 8.dp)) }
        val body = listBody(loaded = state.loaded, failed = state.listFailed && !state.loading, empty = groups.isEmpty)
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (body == ListBody.Loading) {
                item(key = "loading") {
                    if (rememberLoaderVisible(true)) DotWave(modifier = Modifier.padding(horizontal = gutter(), vertical = 24.dp))
                }
            } else if (body == ListBody.Failed) {
                item(key = "failed") { LoadFailed("missions", state.error?.body, handlers.onRefresh) }
            } else if (body == ListBody.Empty) {
                item(key = "empty") {
                    Text(
                        "No missions yet. A mission is long work Control plans with you and runs step by step.",
                        color = o.fgMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = gutter(), vertical = 24.dp),
                    )
                }
            }
            group("Waits on you", groups.waiting, nowSeconds, handlers.onOpen)
            group("Working", groups.running, nowSeconds, handlers.onOpen)
            group("Paused", groups.paused, nowSeconds, handlers.onOpen)
            group("Drafts", groups.drafts, nowSeconds, handlers.onOpen)
            group("Done this week", groups.doneThisWeek, nowSeconds, handlers.onOpen)
            item(key = "actions") {
                Column(modifier = Modifier.padding(horizontal = gutter(), vertical = 12.dp)) {
                    val pauseAll = handlers.onPauseAll
                    if (pauseAll != null && groups.running.isNotEmpty()) {
                        TextButton(onClick = { askingPause = true }, enabled = state.busy == null) {
                            Text("Pause all missions…", color = o.statusFailed)
                        }
                    }
                    handlers.onNewInControl?.let { TextButton(onClick = it) { Text("New mission in Control", color = o.accent) } }
                }
            }
        }
    }
    val pauseAll = handlers.onPauseAll
    if (askingPause && pauseAll != null) {
        BottomSheet(
            title = "Pause all missions?",
            meta = pauseAllMeta(groups.running.size),
            onDismiss = { askingPause = false },
            primary = SheetAction(pauseAllLabel(groups.running.size), enabled = state.busy == null) {
                askingPause = false
                pauseAll()
            },
        ) {
            for (m in groups.running) {
                PhoneRow(title = m.name.ifBlank { "Mission ${m.id}" }, line = missionRowLine(m), word = missionWord(m), divider = false)
            }
            Text("Resume from here or from Control; nothing is lost.", color = o.fgMuted, fontSize = 13.sp)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.group(
    title: String,
    missions: List<Mission>,
    nowSeconds: Long,
    onOpen: (Long) -> Unit,
) {
    if (missions.isEmpty()) return
    item(key = "h-$title") { GroupHeading(title, missions.size) }
    items(missions, key = { "m-$title-${it.id}" }) { m ->
        val wait = m.waitingOn
        Column {
            PhoneRow(
                title = m.name.ifBlank { "Mission ${m.id}" },
                // A waiting mission leads with what it waits on, then how far it got.
                line = if (wait != null) "${missionAskWords(wait)} · ${missionRowLine(m)}" else missionRowLine(m),
                word = if (wait != null) StatusWord.NEEDS_YOU else missionWord(m),
                lead = if (wait != null) "Waiting for you" else missionWord(m)?.label,
                age = relativeTime((wait?.since ?: m.updatedAt).takeIf { it > 0 }, nowSeconds),
                onClick = { onOpen(m.id) },
                divider = missionSpendFraction(m) == null,
            )
            missionSpendFraction(m)?.let { SpendMeter(it) }
        }
    }
}

/** A mission's spend against its budget, as a thin bar under its row; amber near it, red at it. */
@Composable
private fun SpendMeter(fraction: Float) {
    val o = Fleet.colors
    val bar = when {
        fraction >= 1f -> o.statusFailed
        fraction >= 0.8f -> o.statusWaiting
        else -> o.accent
    }
    Box(
        Modifier
            .padding(start = gutter(), end = gutter(), bottom = 10.dp)
            .fillMaxWidth()
            .height(4.dp)
            .background(o.track, RoundedCornerShape(2.dp))
            .semantics { contentDescription = "${(fraction * 100).toInt()} percent of the budget spent" },
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(bar, RoundedCornerShape(2.dp)))
    }
}

private fun gutter() = OrbitTokens.spacing("phone-gutter").dp
