package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.ControlThread
import dev.claudefleet.mobile.model.GraphCard
import dev.claudefleet.mobile.model.GraphStep
import dev.claudefleet.mobile.model.STEPS_PER_WAVE
import dev.claudefleet.mobile.model.StepTone
import dev.claudefleet.mobile.model.ThreadGroup
import dev.claudefleet.mobile.model.groupThreads
import dev.claudefleet.mobile.model.relativeAgo
import dev.claudefleet.mobile.model.threadsSummary
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitColors

/*
 * Control as a Thread room on the phone (mission control hardening,
 * claude-fleet #914 and #921), phone-sized: the threads Control handed out
 * behind one strip under the header, the task graph as a card, and the
 * mission's "Steer" box that hands text to Control.
 */

const val THREADS_STRIP_TAG = "control.threads"
const val THREAD_ROW_TAG = "control.thread."
const val TASK_GRAPH_TAG = "task-graph"
const val STEER_TAG = "mission.steer"

private fun toneColor(t: StepTone, o: OrbitColors): Color = when (t) {
    StepTone.DONE -> o.statusDone
    StepTone.WORKING -> o.statusWorking
    StepTone.WAITING -> o.statusWaiting
    StepTone.FAILED -> o.statusFailed
    StepTone.IDLE -> o.statusIdle
}

/** "Threads · 2 need you · 3 working ›": opens the list. Nothing when Control handed nothing out. */
@Composable
fun ControlThreadsStrip(threads: List<ControlThread>, onOpen: () -> Unit) {
    val summary = threadsSummary(threads) ?: return
    val o = Fleet.colors
    val needs = threads.any { it.group == ThreadGroup.NEEDS }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Show Control's threads", onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(THREADS_STRIP_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(if (needs) o.statusWaiting else o.statusWorking, CircleShape))
        Text("Threads · $summary", color = o.fg2, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("›", color = o.fgMuted, fontSize = 16.sp)
    }
}

/** The threads Control handed out, grouped Needs you / Working / Done today / Earlier. A tap opens the session or the mission. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlThreadsSheet(threads: List<ControlThread>, nowSeconds: Long, onOpen: (ControlThread) -> Unit, onClose: () -> Unit) {
    val o = Fleet.colors
    ModalBottomSheet(onDismissRequest = onClose) {
        Text(
            "Control's threads",
            color = o.fg,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            for ((group, list) in groupThreads(threads)) {
                item(key = "h:${group.name}") {
                    Text(
                        "${group.label} · ${list.size}",
                        color = o.fgMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { it.key }) { t ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(t) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("$THREAD_ROW_TAG${t.key}"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (t.mission) "◇ " else "") + t.title,
                                color = o.fg,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            relativeAgo(t.at.takeIf { it > 0 }, nowSeconds)?.let { Text(it, color = o.fgMuted, fontSize = 12.sp) }
                        }
                        if (t.line.isNotBlank()) Text(t.line, color = o.fgMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A mission's or a proposed tree's steps by wave, each tinted by its state, with its progress. */
@Composable
fun TaskGraphCard(card: GraphCard, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val o = Fleet.colors
    Surface(
        modifier = modifier.fillMaxWidth().testTag(TASK_GRAPH_TAG),
        shape = RoundedCornerShape(10.dp),
        color = o.bgRaise,
        border = BorderStroke(1.dp, o.border),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.title, color = o.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(card.progress, color = o.fgMuted, fontSize = 12.sp)
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.waves.forEachIndexed { i, wave ->
                    if (i > 0) Text("›", color = o.fgMuted, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterVertically))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        wave.take(STEPS_PER_WAVE).forEach { StepPill(it, o) }
                        if (wave.size > STEPS_PER_WAVE) Text("+${wave.size - STEPS_PER_WAVE}", color = o.fgMuted, fontSize = 12.sp)
                    }
                }
            }
            onOpen?.let { open -> TextButton(onClick = open, modifier = Modifier.align(Alignment.End)) { Text("Open full graph", color = o.accent) } }
        }
    }
}

@Composable
private fun StepPill(s: GraphStep, o: OrbitColors) {
    val tint = toneColor(s.tone, o)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = o.bg,
        border = BorderStroke(if (s.current) 2.dp else 1.dp, if (s.current) tint else o.border),
        modifier = Modifier.width(150.dp),
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tint, CircleShape))
            Column(modifier = Modifier.padding(start = 6.dp)) {
                Text(s.title, color = o.fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.sub, color = o.fgMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * "Steer <mission>…": the text goes to Control's agent with the mission
 * named in front; Control does the steering. Says why it cannot send rather
 * than hiding, and lists what this phone sent.
 */
@Composable
fun SteerComposer(missionName: String, steer: MissionSteerUi, nowSeconds: Long, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    var text by rememberSaveable(missionName) { mutableStateOf("") }
    // The draft goes only once Control has it: a failed send keeps it for Send again.
    // Counted, not the receipts' size: those are capped at 20 (review w21-15).
    val baseline = remember(missionName) { steer.delivered }
    LaunchedEffect(steer.delivered) { if (steer.delivered > baseline) text = "" }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(STEER_TAG), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in steer.sent.takeLast(3)) {
            Text(
                "Sent to Control${relativeAgo(r.at, nowSeconds)?.let { " · $it" }.orEmpty()}: ${r.text}",
                color = o.fgMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        steer.error?.let { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${e.title}. ${e.body}", color = o.danger, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = steer.onDismissError) { Text("Dismiss") }
            }
        }
        steer.blocked?.let { Text(it, color = o.fgMuted, fontSize = 13.sp) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = steer.blocked == null && !steer.sending,
                placeholder = { Text("Steer $missionName…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { steer.onSend(text) },
                enabled = steer.blocked == null && !steer.sending && text.isNotBlank(),
            ) { Text(if (steer.sending) "Sending…" else "Send") }
        }
    }
}
