package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.RepoActions
import dev.claudefleet.mobile.model.PlanSaid
import dev.claudefleet.mobile.model.planFile
import dev.claudefleet.mobile.store.Prefs
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/*
 * The "Plan ready" card (claude-fleet `docs/plan-first.md`): the agent has
 * said its plan is ready and named the file, and that file is among the
 * session's changes. The card names the file and offers Read plan (the Files
 * tab), Approve and start (one prompt) and Ask for changes… (the composer,
 * with the file named). Nothing advances by itself, and the next prompt,
 * whoever sends it, takes the card away.
 */

/** The plan a card is due for: the file, and the announcement it was said in. */
data class PlanReady(val path: String, val at: String)

/**
 * The "Plan ready" announcements answered on this phone (approved or hidden),
 * one per session, so a card does not come back while its approval waits in
 * the hub's queue. An instance, like [DraftMemory], so each test gets its
 * own; given [prefs], it outlives the process. The newest [MAX_SESSIONS].
 *
 * Asked from the work dispatcher (the look for the file, an approval the hub
 * took) and from a tap (Hide), so the map is swapped whole, never edited.
 */
class PlanSettled(private val prefs: Prefs? = null) {
    /** Session id to the announcement answered there, the oldest first. */
    private val settled = MutableStateFlow(load(prefs))

    /** The hub these were answered on; null before the first pairing this build saw. */
    private var hub: String? = prefs?.getStringList(HUB_KEY)?.firstOrNull()

    /** Another hub's session ids are not this one's: pairing with it forgets the last one's ([DraftMemory.forHub]). */
    fun forHub(hubUrl: String) {
        val was = hub
        if (was == hubUrl) return
        hub = hubUrl
        prefs?.putStringList(HUB_KEY, listOf(hubUrl))
        if (was == null) return
        settled.value = emptyMap()
        save(emptyMap())
    }

    fun isSettled(sessionId: Long, plan: PlanReady): Boolean = settled.value[sessionId] == announcement(plan)

    fun settle(sessionId: Long, plan: PlanReady) {
        // Taken out and put back, so the most recently answered session is the last one forgotten.
        save(settled.updateAndGet { was -> newest((was - sessionId).toList() + (sessionId to announcement(plan))) })
    }

    private fun save(now: Map<Long, String>) {
        prefs?.putStringList(KEY, now.flatMap { (id, said) -> listOf(id.toString(), said) })
    }

    companion object {
        /** The prefs key: a flat list of session id, announcement, session id, announcement, … */
        const val KEY = "plan_ready_settled"
        const val HUB_KEY = "plan_ready_settled_hub"
        const val MAX_SESSIONS = 100

        /** One announcement: the prompt it was said after, and the file. */
        private fun announcement(plan: PlanReady): String = "${plan.at} ${plan.path}"

        /** The last [MAX_SESSIONS] of [pairs], in their order. */
        private fun newest(pairs: List<Pair<Long, String>>): Map<Long, String> = pairs.takeLast(MAX_SESSIONS).toMap()

        private fun load(prefs: Prefs?): Map<Long, String> {
            val flat = prefs?.getStringList(KEY) ?: return emptyMap()
            return newest(flat.chunked(2).mapNotNull { pair -> pair.getOrNull(0)?.toLongOrNull()?.let { id -> pair.getOrNull(1)?.let { id to it } } })
        }
    }
}

/**
 * Whether a session's "Plan ready" card is due. [heard] is told what the
 * agent said last and whether it still works; once it has said so and
 * stopped, the session's changes are read for the file it names, never while
 * it works. A read that fails keeps what was shown: a dropped connection is
 * not "no plan".
 */
class PlanReadyViewModel(
    private val sessionId: Long,
    private val repo: RepoActions,
    private val settled: PlanSettled,
    private val scope: CoroutineScope,
) {
    private val _plan = MutableStateFlow<PlanReady?>(null)

    /** The plan waiting for a yes; null while there is none, or it was answered here. */
    val plan: StateFlow<PlanReady?> = _plan.asStateFlow()

    private var looking: Job? = null

    fun heard(said: PlanSaid?, working: Boolean): Job? {
        looking?.cancel()
        // The conversation moved on, or this is another announcement: the card was about the last one.
        _plan.update { shown -> shown?.takeIf { said != null && it.at == said.at } }
        if (said == null || working) return null
        return scope.launch {
            val path = try {
                planFile(repo.changes(sessionId), said.text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return@launch
            }
            _plan.value = path?.let { PlanReady(it, said.at) }?.takeUnless { settled.isSettled(sessionId, it) }
        }.also { looking = it }
    }

    /** Approved or hidden: no card for this announcement again on this phone. */
    fun settle(plan: PlanReady) {
        settled.settle(sessionId, plan)
        _plan.update { shown -> shown?.takeUnless { it == plan } }
    }
}

/** The card's state and taps, as [SessionScreen] takes them; null draws no card. */
data class PlanReadyHost(
    val plan: PlanReady,
    /** Why Approve cannot go out now, in words; null when it can. */
    val blocked: String? = null,
    /** An approval is on the wire. */
    val sending: Boolean = false,
    /** Open the file, rendered; null where the hub serves no files. */
    val onRead: (() -> Unit)? = null,
    /** Send the approval; null on a hub that keeps no prompt for a session. */
    val onApprove: (() -> Unit)? = null,
    val onDismiss: () -> Unit = {},
)

internal const val PLAN_READY_TAG = "plan.ready"
internal const val PLAN_READY_APPROVE_TAG = "plan.ready.approve"

/**
 * Drawn as a question the agent asks ([QuestionCard]): the same tint and the
 * same plain full-width rows, all alike. Approve is never the filled button,
 * and a tap in the first moments after the card appears is ignored (plan v2 §3).
 */
@Composable
internal fun PlanReadyCard(host: PlanReadyHost, agent: String, onAskChanges: (() -> Unit)?, modifier: Modifier = Modifier) {
    val plan = host.plan
    // The question card's amber tint: on it, text is fg or fg-2 and text
    // buttons are fg (review r11; PlanReadyCardTest measures each one).
    val tint = Fleet.colors.waitingSoft
    val onTint = ButtonDefaults.textButtonColors(contentColor = Fleet.colors.fg)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(PLAN_READY_TAG),
        color = tint,
        contentColor = Fleet.colors.fg,
        shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
        border = BorderStroke(1.dp, Fleet.colors.waitingLine),
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plan ready", style = Fleet.type.textLg, modifier = Modifier.weight(1f))
                TextButton(onClick = host.onDismiss, colors = onTint) { Text("Hide") }
            }
            SelectionContainer { Text(plan.path, style = Fleet.type.code, color = Fleet.colors.fg2) }
            Spacer(Modifier.height(4.dp))
            Text("$agent wrote a plan and is waiting. Nothing starts until you approve it.", style = Fleet.type.textMd, color = Fleet.colors.fg2)
            var armed by remember(plan) { mutableStateOf(false) }
            LaunchedEffect(plan) {
                delay(ANSWER_ARM_MS)
                armed = true
            }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                host.onRead?.let { PlanRow(label = "Read plan", enabled = true, onClick = it) }
                host.onApprove?.let { approve ->
                    PlanRow(
                        label = if (host.sending) "Sending…" else "Approve and start",
                        enabled = host.blocked == null && !host.sending,
                        onClick = { if (armed) approve() },
                        modifier = Modifier.testTag(PLAN_READY_APPROVE_TAG),
                    )
                }
                onAskChanges?.let { PlanRow(label = "Ask for changes…", enabled = true, onClick = it) }
            }
            host.blocked?.takeIf { host.onApprove != null }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = Fleet.type.textXs, color = Fleet.colors.fg2)
            }
        }
    }
}

/**
 * One of the card's answers: its full width and at least a thumb tall, as the
 * question card's rows are. One composable with no filled or selected form,
 * so Approve cannot be drawn as the default.
 */
@Composable
private fun PlanRow(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(OrbitTokens.radius("radius-lg").dp)
    Text(
        text = label,
        style = Fleet.type.textMd,
        color = if (enabled) Fleet.colors.fg else Fleet.colors.fgMuted,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTokens.spacing("touch-min").dp)
            .clip(shape)
            .border(1.dp, Fleet.colors.controlBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    )
}
