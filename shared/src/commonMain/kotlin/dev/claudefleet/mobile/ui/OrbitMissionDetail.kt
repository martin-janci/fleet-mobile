package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.MissionDetail
import dev.claudefleet.mobile.model.MissionPlan
import dev.claudefleet.mobile.model.autonomyLabel
import dev.claudefleet.mobile.model.dollars
import dev.claudefleet.mobile.model.key
import dev.claudefleet.mobile.model.pauseMove
import dev.claudefleet.mobile.model.runEstimateLine
import dev.claudefleet.mobile.model.SpendAsk
import dev.claudefleet.mobile.model.spendAsk
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.Comet
import dev.claudefleet.mobile.ui.kit.StatusWord
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.SheetAction

/*
 * One mission in the New layout (board MobileControl, the mission panel):
 * the name and how far it got, the grant waiting for a person as a card,
 * what runs now with Comet trails, and the plan ticking as steps land. The
 * cards, Go and Pause are the Missions sheet's own, so Classic and New press
 * the same calls. A grant is signed from its review sheet ([GrantSheet], gap
 * plan G5.7), which says every term and pre-selects none; Not now puts the
 * card away on this phone. A failed task offers Retry.
 */

const val MISSION_GRANT_TAG = "mission.grant"
const val MISSION_SPEND_TAG = "mission.spend"
const val MISSION_SPEND_APPROVE_TAG = "mission.spend.approve"
const val MISSION_SPEND_DENY_TAG = "mission.spend.deny"
const val MISSION_GRANT_NOT_NOW_TAG = "mission.grant.notNow"
const val MISSION_PLAN_TAG = "mission.plan."
const val MISSION_RUNNING_TAG = "mission.running."
const val MISSION_GRANT_REVIEW_TAG = "mission.grant.review"
const val MISSION_RETRY_TAG = "mission.retry."

/** How long a grant may run, as the review sheet offers it (the hub takes 1 to 168 hours). */
internal val GRANT_HOURS: List<Int> = listOf(8, 24, 72)

/** The spend caps the review sheet offers, in dollars; null is "No cap", said in words. */
internal val GRANT_BUDGETS: List<Long?> = listOf(5L, 20L, 50L, null)

/** "Sign level 2 for 24 h, up to $20": the sheet's verb, once both terms are picked; null until then. */
internal fun grantSignLabel(level: Int, hours: Int?, budget: Long?, budgetPicked: Boolean): String? {
    if (hours == null || !budgetPicked) return null
    return "Sign level $level for $hours h" + (budget?.let { ", up to \$$it" } ?: ", no cap")
}

/** A failed task may be tried again: its node failed (not blocked, which waits on another task). */
internal fun retryable(r: PlanRow, state: String): Boolean = r.mark == PlanMark.FAILED && state == "failed"

/** How a plan line starts: ✓ done, a Comet while it runs, ○ still to do. */
enum class PlanMark(val glyph: String, val word: StatusWord?) {
    DONE("✓", StatusWord.DONE),
    RUNNING("", StatusWord.WORKING),
    NEEDS_YOU("○", StatusWord.NEEDS_YOU),
    FAILED("✗", StatusWord.FAILED),
    TODO("○", null),
}

data class PlanRow(val itemId: Long, val title: String, val mark: PlanMark, val note: String?)

/** A node's state as a plan mark, as the desktop's `toneOf` files them. */
internal fun planMark(state: String): PlanMark = when (state) {
    "done" -> PlanMark.DONE
    "running", "doing", "verifying" -> PlanMark.RUNNING
    "failed", "blocked" -> PlanMark.FAILED
    // A proposal waits for a person to accept it.
    "proposed" -> PlanMark.NEEDS_YOU
    else -> PlanMark.TODO
}

private fun noteOf(mark: PlanMark, state: String): String? = when (mark) {
    PlanMark.NEEDS_YOU -> "needs you"
    PlanMark.FAILED -> state
    PlanMark.TODO -> state.takeIf { it == "held" }
    else -> null
}

/**
 * The plan in the order the loop takes it: by wave, then by task. A task an
 * open card asks about needs you whatever its node says. A mission without a
 * graph yet (a draft) lists its tasks by their own status.
 */
internal fun planRows(detail: MissionDetail): List<PlanRow> {
    val titles = detail.items.associate { it.id to (it.title.ifBlank { it.key ?: "Task ${it.id}" }) }
    val asked = detail.plan?.cards.orEmpty().filter { it.state == "open" }.mapNotNull { it.workItemId }.toSet()
    val nodes = detail.graph.nodes
    val rows = if (nodes.isNotEmpty()) {
        nodes.sortedWith(compareBy({ it.wave }, { it.itemId })).map { n -> Triple(n.itemId, n.state, planMark(n.state)) }
    } else {
        detail.items.map { i ->
            val state = if (i.statusCategory == "done") "done" else ""
            Triple(i.id, state, planMark(state))
        }
    }
    return rows.map { (id, state, mark0) ->
        val mark = if (id in asked && mark0 != PlanMark.DONE) PlanMark.NEEDS_YOU else mark0
        PlanRow(id, titles[id] ?: "Task $id", mark, noteOf(mark, state))
    }
}

/** "Mission · 5 of 14 steps", with the state when it is not running and why it stands still. */
internal fun missionHeadLine(m: Mission, phase: String?): String = buildList {
    add("Mission")
    add(if (m.total > 0) "${m.done} of ${m.total} steps" else "no steps yet")
    if (m.state != "active") add(m.state)
    if (phase == "blocked" || phase == "waiting") add(phase)
}.joinToString(" · ")

/** "Running · 2 tasks"; null when nothing runs. */
internal fun runningLine(rows: List<PlanRow>): String? {
    val n = rows.count { it.mark == PlanMark.RUNNING }
    return if (n == 0) null else "Running · $n ${if (n == 1) "task" else "tasks"}"
}

/** The grant a mission waits on: what it asks for, said in words. */
data class GrantAsk(val level: Int, val line: String, val why: String?)

/**
 * A grant waits for a person when the mission asks for more than it gets,
 * the fleet's ceiling would allow it, and nobody has signed one. Levels 0
 * and 1 need no grant.
 */
internal fun grantAsk(plan: MissionPlan?): GrantAsk? {
    val a = plan?.autonomy ?: return null
    if (!a.enabled || a.grant != null || a.asked < 2 || a.effective >= a.asked || a.ceiling < a.asked) return null
    val label = autonomyLabel(a.asked)
    return GrantAsk(a.asked, "Level ${a.asked}: ${label.replaceFirstChar { it.uppercase() }}.", a.why.ifBlank { null })
}

/** The New layout's mission detail, drawn in the Missions sheet in place of the plain one. */
@Composable
fun OrbitMissionDetail(detail: MissionDetail, state: MissionsUiState, handlers: MissionsHandlers) {
    val o = Fleet.colors
    val m = detail.mission
    val plan = detail.plan
    val rows = remember(detail) { planRows(detail) }
    val titles = remember(detail.items) { detail.items.associate { it.id to (it.key ?: it.title) } }
    val ask = remember(plan) { grantAsk(plan) }
    val spend = remember(detail) { spendAsk(detail) }
    var notNow by rememberSaveable(m.id) { mutableStateOf(false) }
    var reviewing by remember(m.id) { mutableStateOf(false) }
    val nodeState = remember(detail.graph) { detail.graph.nodes.associate { it.itemId to it.state } }
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = handlers.onBack) { Text("‹ Missions", color = o.accent) }
            Box(Modifier.weight(1f))
            if (rememberLoaderVisible(state.loading || state.busy != null)) Comet(modifier = Modifier.padding(end = 8.dp))
            TextButton(onClick = handlers.onRefresh, enabled = !state.loading) { Text("Refresh", color = o.fg2) }
        }
        Column(modifier = Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                m.name.ifBlank { "Mission ${m.id}" },
                color = o.fg,
                fontSize = 19.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Text(missionHeadLine(m, detail.phase), color = o.fgMuted, fontSize = 14.sp, lineHeight = 20.sp)
            if (m.goal.isNotBlank()) Text(m.goal, color = o.fg2, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        ErrorBanner(state.error, onDismiss = handlers.onDismissError)
        Outcome(state)
        LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (ask != null && !notNow) {
                item(key = "grant") {
                    GrantCard(
                        ask,
                        onReview = { reviewing = true }.takeIf { state.canSignGrant && detail.mayChange },
                        onNotNow = { notNow = true },
                    )
                }
            }
            if (spend != null) {
                item(key = "spend") {
                    SpendAskCard(
                        spend,
                        canAnswer = state.canAnswerSpend && detail.mayChange && state.busy == null,
                        onApprove = { handlers.onApproveSpend(spend) },
                        onDeny = { handlers.onDenySpend(spend) },
                    )
                }
            }
            // The brake's own card is the spend ask above; it is not listed twice.
            val cards = plan?.cards.orEmpty().filter { it.id != spend?.cardId }
            if (cards.isNotEmpty()) {
                item(key = "cards-h") { GroupHeading("Waiting for you", cards.size) }
                items(cards, key = { "card:${it.id}" }) { c -> CardRow(c, state, detail.mayChange, handlers) }
            }
            val running = rows.filter { it.mark == PlanMark.RUNNING }
            runningLine(rows)?.let { line ->
                item(key = "running-h") { Heading(line) }
                items(running, key = { "run:${it.itemId}" }) { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 6.dp).testTag("$MISSION_RUNNING_TAG${r.itemId}"),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Comet()
                        Text(r.title, color = o.fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (rows.isNotEmpty()) {
                item(key = "plan-h") { Heading("Plan") }
                items(rows, key = { "plan:${it.itemId}" }) { r ->
                    val canRetry = state.canRetry && detail.mayChange && state.busy == null && retryable(r, nodeState[r.itemId].orEmpty())
                    PlanLine(r, onRetry = { handlers.onRetryItem(r.itemId) }.takeIf { canRetry })
                }
            }
            if (plan == null) {
                item(key = "noplan") {
                    Text(
                        if (m.state == "draft") "A draft: plan and start it in Control on the desktop." else "This mission takes no more steps.",
                        color = o.fgMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = gutter, vertical = 8.dp),
                    )
                }
            } else {
                item(key = "steps-h") { Heading("Next steps") }
                if (plan.steps.isEmpty()) {
                    item(key = "steps-none") { Text("Nothing is ready.", color = o.fgMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = gutter)) }
                }
                items(plan.steps, key = { "step:${it.key()}" }) { s ->
                    StepRow(s, s.itemId?.let { titles[it] }, state.canStart && detail.mayChange && state.busy == null, handlers)
                }
                item(key = "spent") {
                    val a = plan.autonomy
                    Text(
                        "Autonomy ${a.effective}: ${autonomyLabel(a.effective)} · spent ${dollars(plan.costMicros)}" +
                            (plan.runEstimate?.let { "\n" + runEstimateLine(it) } ?: ""),
                        color = o.fgMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = gutter, vertical = 6.dp),
                    )
                }
            }
            item(key = "moves") {
                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                    if (plan != null && state.canStart && detail.mayChange && plan.steps.count { it.kind != "ask" } > 1) {
                        TextButton(onClick = { handlers.onStart(null) }, enabled = state.busy == null) { Text("Take every step", color = o.accent) }
                    }
                    if (state.canPause && detail.mayChange && m.pauseMove() != null) {
                        TextButton(onClick = handlers.onTogglePause, enabled = state.busy == null) {
                            Text(if (m.state == "active") "Pause" else "Resume", color = o.fg2)
                        }
                    }
                }
            }
            item(key = "footer") {
                Text(
                    "Verified checks are run by the hub, never by Jev.",
                    color = o.fgMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = gutter, vertical = 8.dp),
                )
            }
        }
    }
    if (reviewing && ask != null) {
        GrantSheet(
            name = m.name.ifBlank { "Mission ${m.id}" },
            ask = ask,
            busy = state.busy != null,
            onDismiss = { reviewing = false },
            onSign = { hours, budget ->
                reviewing = false
                handlers.onSignGrant(ask.level, hours, budget)
            },
        )
    }
}

/**
 * Review and sign (gap plan G5.7, board MobileControl): every term of the
 * grant in words — the level and what it lets the loop do, how long, the
 * spend cap — and Sign only once a duration and a cap are picked. Nothing is
 * pre-selected: a signature is a deliberate tap, never a default.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GrantSheet(name: String, ask: GrantAsk, busy: Boolean, onDismiss: () -> Unit, onSign: (Int, Long?) -> Unit) {
    val o = Fleet.colors
    var hours by remember { mutableStateOf<Int?>(null) }
    var budget by remember { mutableStateOf<Long?>(null) }
    var budgetPicked by remember { mutableStateOf(false) }
    val label = grantSignLabel(ask.level, hours, budget, budgetPicked)
    BottomSheet(
        title = "Sign the autonomy grant",
        meta = name,
        onDismiss = onDismiss,
        primary = SheetAction(label ?: "Pick how long and a cap", enabled = label != null && !busy) {
            hours?.let { h -> onSign(h, budget) }
        },
        scrollable = true,
    ) {
        Text(ask.line, color = o.fg, fontSize = 15.sp, lineHeight = 21.sp)
        ask.why?.let { Text(it, color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp) }
        Text("For how long", color = o.fgMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (h in GRANT_HOURS) {
                FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text("$h h") })
            }
        }
        Text("Spend cap", color = o.fgMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in GRANT_BUDGETS) {
                FilterChip(
                    selected = budgetPicked && budget == b,
                    onClick = { budget = b; budgetPicked = true },
                    label = { Text(b?.let { "\$$it" } ?: "No cap") },
                )
            }
        }
        Text(
            "The loop then runs on its own within these terms until the grant ends. Pause the mission to stop it at any time; Pause all ends every grant.",
            color = o.fgMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        color = Fleet.colors.fgMuted,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = OrbitTokens.spacing("phone-gutter").dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun PlanLine(r: PlanRow, onRetry: (() -> Unit)? = null) {
    val o = Fleet.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = 4.dp)
            .testTag("$MISSION_PLAN_TAG${r.itemId}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(16.dp), contentAlignment = Alignment.Center) {
            if (r.mark == PlanMark.RUNNING) {
                Comet()
            } else {
                Text(r.mark.glyph, color = r.mark.word?.color(o) ?: o.fgMuted, fontSize = 14.sp)
            }
        }
        Text(
            r.title + (r.note?.let { " ($it)" } ?: ""),
            color = if (r.mark == PlanMark.DONE) o.fgMuted else o.fg,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.testTag("$MISSION_RETRY_TAG${r.itemId}")) { Text("Retry", color = o.accent) }
        }
    }
}

@Composable
private fun GrantCard(ask: GrantAsk, onReview: (() -> Unit)?, onNotNow: () -> Unit) {
    val o = Fleet.colors
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = 8.dp)
            .background(o.bgSunk, shape)
            .border(1.dp, o.statusWaiting, shape)
            .padding(14.dp)
            .testTag(MISSION_GRANT_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Sign the autonomy grant", color = o.fg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(ask.line, color = o.fg2, fontSize = 14.sp, lineHeight = 20.sp)
        ask.why?.let { Text(it, color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp) }
        if (onReview == null) {
            Text(
                "Review and sign it in Control on the desktop: this pairing cannot sign a grant.",
                color = o.fgMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onReview != null) {
                androidx.compose.material3.OutlinedButton(onClick = onReview, modifier = Modifier.testTag(MISSION_GRANT_REVIEW_TAG)) {
                    Text("Review and sign…")
                }
            }
            TextButton(onClick = onNotNow, modifier = Modifier.testTag(MISSION_GRANT_NOT_NOW_TAG)) { Text("Not now", color = o.fg2) }
        }
    }
}

/**
 * The spend ask (redesign 14.16, board MobileMissions): who asked, what was
 * spent of what, what Approve adds and the new limit, and why. Approve and
 * Deny are both plain outlined buttons — neither is filled, focused or
 * pre-selected, so nothing answers it but a deliberate tap.
 */
@Composable
internal fun SpendAskCard(ask: SpendAsk, canAnswer: Boolean, onApprove: () -> Unit, onDeny: () -> Unit) {
    val o = Fleet.colors
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = 8.dp)
            .background(o.bgSunk, shape)
            .border(1.dp, o.statusWaiting, shape)
            .padding(14.dp)
            .testTag(MISSION_SPEND_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Approve more spend?", color = o.fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
        Text("Spent ${dollars(ask.spentMicros)} of ${dollars(ask.budgetMicros)}", color = o.fg2, fontSize = 14.sp, lineHeight = 20.sp)
        Text(
            "Asks for ${dollars(ask.moreMicros)} more (new limit ${dollars(ask.newLimitMicros)})",
            color = o.fg2,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        if (ask.why.isNotBlank()) Text("Why: ${ask.why}", color = o.fgMuted, fontSize = 13.sp, lineHeight = 18.sp)
        Text(
            "Nothing is pre-selected. Deny tells the lead to finish within ${dollars(ask.budgetMicros)}.",
            color = o.fgMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = onDeny, enabled = canAnswer, modifier = Modifier.testTag(MISSION_SPEND_DENY_TAG)) {
                Text("Deny")
            }
            androidx.compose.material3.OutlinedButton(onClick = onApprove, enabled = canAnswer, modifier = Modifier.testTag(MISSION_SPEND_APPROVE_TAG)) {
                Text("Approve ${dollars(ask.moreMicros)}")
            }
        }
    }
}
