package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.ControlThread
import dev.claudefleet.mobile.model.Escalation
import dev.claudefleet.mobile.model.GraphCard
import dev.claudefleet.mobile.ui.theme.Fleet

/*
 * Control's activity as one row of counts under the view pills (the phone's
 * port of the desktop's ControlActivity, claude-fleet #1046): "3 need you ·
 * ↗ 2 handed off · Threads 9". Each count opens its list in a sheet, with
 * every action the cards had. Above the composer stay only what the chat
 * waits on (confirms, other sessions' forms), so the conversation keeps the
 * screen (Martin, 2026-11-11: "cannot see chat").
 */

enum class ControlActivity { Needs, Handoffs, Threads }

const val CONTROL_ACTIVITY_TAG = "control.activity."

/** One count of the row: what it says, and whether it is the person's to act on (amber). */
data class ActivityCount(val section: ControlActivity, val label: String, val attention: Boolean)

/**
 * The row's counts, in order; a section with nothing in it draws no chip,
 * and an empty list draws no row. [needs] is every escalation, not only the
 * ones a card would draw: Inbox has the rest, and the sheet links there.
 */
fun activityCounts(needs: Int, handoffs: List<HandoffChip>, plan: Boolean, threads: List<ControlThread>): List<ActivityCount> = buildList {
    if (needs > 0) add(ActivityCount(ControlActivity.Needs, "$needs need you", attention = true))
    val handed = handoffs.size
    if (handed > 0 || plan) {
        val label = if (handed > 0) "↗ $handed handed off" else "↗ Plan"
        add(ActivityCount(ControlActivity.Handoffs, label, attention = handoffs.any { it.state == "waiting" }))
    }
    if (threads.isNotEmpty()) add(ActivityCount(ControlActivity.Threads, "Threads ${threads.size} ›", attention = false))
}

/** The row itself: one line of chips that scrolls sideways; nothing when every count is zero. */
@Composable
fun ControlActivityRow(counts: List<ActivityCount>, onOpen: (ControlActivity) -> Unit) {
    if (counts.isEmpty()) return
    val o = Fleet.colors
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (c in counts) {
            Surface(
                color = if (c.attention) o.statusWaiting.copy(alpha = 0.10f) else o.bgPane,
                border = BorderStroke(1.dp, if (c.attention) o.statusWaiting else o.controlBorder),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clickable(role = Role.Button, onClickLabel = "Show") { onOpen(c.section) }
                    .testTag(CONTROL_ACTIVITY_TAG + c.section.name),
            ) {
                Text(
                    c.label,
                    style = Fleet.type.textSm,
                    color = if (c.attention) o.statusWaiting else o.fg2,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** A count's list, in a sheet: a heading, then the cards as they were above the composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivitySheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val o = Fleet.colors
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                title,
                color = o.fg,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { heading() },
            )
            content()
        }
    }
}

/** How many escalation cards the Needs you sheet draws before "N more in Inbox". */
const val ESCALATIONS_IN_SHEET = 20

/** What needs the person, with Open and Later; Open and the Inbox link close the sheet first. */
@Composable
fun ControlNeedsSheet(
    all: List<Escalation>,
    onOpen: (Long) -> Unit,
    onLater: (String) -> Unit,
    onInbox: () -> Unit,
    onClose: () -> Unit,
) {
    ActivitySheet("Needs you · ${all.size}", onClose) {
        EscalationCards(
            all = all,
            onOpen = { onClose(); onOpen(it) },
            onLater = onLater,
            onMore = { onClose(); onInbox() },
            shown = ESCALATIONS_IN_SHEET,
        )
        TextButton(onClick = { onClose(); onInbox() }, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text("All in Inbox ›", style = Fleet.type.textSm, color = Fleet.colors.accent)
        }
    }
}

/** What Control handed on, what it watches, and the newest proposed plan. */
@Composable
fun ControlHandoffsSheet(
    chips: List<HandoffChip>,
    watching: String?,
    plan: GraphCard?,
    onOpenSession: (Long) -> Unit,
    onClose: () -> Unit,
) {
    ActivitySheet("Handed off", onClose) {
        plan?.let { TaskGraphCard(it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        HandoffChips(chips, onOpenSession = { onClose(); onOpenSession(it) }, watching = watching)
    }
}

/** The row and whichever sheet is open, as Control's header draws them. */
@Composable
fun ControlActivityBar(
    escalations: List<Escalation>,
    handoffs: List<HandoffChip>,
    watching: String?,
    plan: GraphCard?,
    threads: List<ControlThread>,
    open: ControlActivity?,
    onToggle: (ControlActivity?) -> Unit,
    onOpenSession: (Long) -> Unit,
    onLater: (String) -> Unit,
    onInbox: () -> Unit,
) {
    ControlActivityRow(activityCounts(escalations.size, handoffs, plan != null, threads)) { onToggle(it) }
    // A sheet whose last card went closes (the desktop's rule), not on a later count.
    val empty = when (open) {
        ControlActivity.Needs -> escalations.isEmpty()
        ControlActivity.Handoffs -> handoffs.isEmpty() && plan == null
        else -> false
    }
    LaunchedEffect(open, empty) { if (empty) onToggle(null) }
    if (empty) return
    when (open) {
        ControlActivity.Needs ->
            ControlNeedsSheet(escalations, onOpenSession, onLater, onInbox, onClose = { onToggle(null) })
        ControlActivity.Handoffs ->
            ControlHandoffsSheet(handoffs, watching, plan, onOpenSession, onClose = { onToggle(null) })
        // The threads have their own sheet (ControlThreadsSheet): [onToggle] opens it.
        ControlActivity.Threads, null -> Unit
    }
}
