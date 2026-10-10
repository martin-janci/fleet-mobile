package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.AgentActions
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.ControlActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.ConfirmRequest
import dev.claudefleet.mobile.model.ControlHandoff
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.OperatorStatus
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/*
 * Control on the phone (redesign 9.8 and 14.7, the MobileControl board): the
 * fleet's coordinator as the Control tab itself rather than a row that opens
 * its session. The tab is Control's conversation under a "Control · N need
 * you" header with its views, and the calls Control waits on a person's yes
 * for (9.2's confirms) as cards above the composer, Approve and Deny, neither
 * pre-selected. New layout only; the Classic bar keeps the agent button.
 */

/** Control's views across the top of the tab. Sessions and Missions open their own screens. */
enum class ControlView { Chat, Sessions, Missions, PullRequests }

data class ControlUiState(
    /** The hub offers the coordinator to this pairing at all. */
    val available: Boolean = false,
    /** `operator_status` answered at least once (or the hub has no such tool). */
    val known: Boolean = false,
    /** Control's session, when it has one that takes messages. */
    val sessionId: Long? = null,
    /** Why it cannot take one: `absent`, `lost`, `no_mcp`, `token_revoked`, `no_host`, `host_down`. */
    val blocked: String? = null,
    val host: String? = null,
    val waking: Boolean = false,
    /** This device may answer Control's confirms (the hub lists both tools to it). */
    val canConfirm: Boolean = false,
    val confirms: List<ConfirmRequest> = emptyList(),
    /** Nonces with an answer in flight. */
    val answering: Set<String> = emptySet(),
    /** What Control handed on, oldest first, as chips (redesign 9.3); empty on a hub without the receipts. */
    val handoffs: List<HandoffChip> = emptyList(),
    val error: Friendly? = null,
    /** The stream to the hub is up: while it is not, nothing here can be asked. */
    val connected: Boolean = true,
    /** The last `operator_status` read failed: Control's state is unknown, not "not running". */
    val statusFailed: Boolean = false,
) {
    /** Waking may help: nothing runs, or what ran is gone or lost its tools. */
    val canWake: Boolean get() = available && connected && !statusFailed && sessionId == null && (blocked == null || blocked in WAKEABLE)
}

/**
 * What the Control tab says before there is a conversation. A failed status
 * read is not "Control is not running" — nothing is known — and a hub that is
 * down is said to be down rather than asked about.
 */
internal fun controlWaitingLine(state: ControlUiState): String = when {
    !state.available -> "The hub does not offer Control to this device."
    !state.connected -> "Not connected to the hub. Control's state shows once it answers."
    state.waking -> "Waking Control…"
    state.statusFailed -> "Couldn't read Control's state."
    !state.known -> "Asking the hub about Control…"
    else -> controlBlockedLine(state.blocked, state.host)
}

private val WAKEABLE = setOf("absent", "lost", "no_mcp")

/** The blocked state in words, and what the person can do about it. */
fun controlBlockedLine(blocked: String?, host: String?): String {
    val on = host?.let { " ($it)" }.orEmpty()
    return when (blocked) {
        null, "absent" -> "Control is not running. Wake it to start the chat."
        "lost" -> "Control's session is gone. Wake it to start a new one."
        "no_mcp" -> "Control cannot reach the hub's tools. Waking it restarts it with them."
        "token_revoked" -> "Control's token was revoked. Mint a new one on the desktop: Settings › Devices."
        "no_host" -> "Control has no host to run on. Choose one on the desktop: Settings › Control."
        "host_down" -> "Control's host$on does not answer. It comes back when the host does."
        // The hub's own word for it is not a sentence; it stays off the screen.
        else -> "Control cannot take a message right now."
    }
}

/**
 * Control's state on the phone. [attach] while the tab shows: it reads
 * `operator_status` once and Control's confirms every [pollMs] (the hub's
 * `confirm:changed` frame is not on the phone's stream yet). [wake] is the
 * one write, and only on a tap: `ensure_operator` may start a session.
 */
class ControlViewModel(
    private val fleet: FleetState,
    private val actions: ControlActions,
    private val agent: AgentActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    private val pollMs: Long = CONFIRM_POLL_MS,
) {
    private data class Local(
        val known: Boolean = false,
        val status: OperatorStatus? = null,
        val woken: Long? = null,
        val waking: Boolean = false,
        val confirms: List<ConfirmRequest> = emptyList(),
        val answering: Set<String> = emptySet(),
        val handoffs: List<ControlHandoff> = emptyList(),
        val error: Friendly? = null,
        val statusFailed: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private var loop: Job? = null

    val state: StateFlow<ControlUiState> =
        combine(local, fleet.capabilities, fleet.sessions, fleet.status) { l, caps, rows, status -> assemble(l, caps, rows, status) }
            .stateIn(scope, SharingStarted.Eagerly, assemble(local.value, fleet.capabilities.value, fleet.sessions.value, fleet.status.value))

    private fun assemble(l: Local, caps: HubCapabilities, rows: List<SessionRow>, status: ConnectionStatus): ControlUiState {
        val available = canWrite && caps.agent
        val op = l.status
        val sessionId = op?.session?.id?.takeIf { op.ready } ?: l.woken
        return ControlUiState(
            available = available,
            known = l.known || !caps.operatorStatus,
            sessionId = if (available) sessionId else null,
            blocked = op?.blocked?.takeIf { sessionId == null },
            host = op?.host,
            waking = l.waking,
            canConfirm = caps.confirms,
            confirms = if (caps.confirms) l.confirms else emptyList(),
            answering = l.answering,
            handoffs = if (caps.handoffs) handoffChips(l.handoffs, rows) else emptyList(),
            error = l.error,
            connected = status is ConnectionStatus.Connected,
            statusFailed = l.statusFailed,
        )
    }

    /** Follow Control while its tab shows. */
    fun attach(): Job {
        loop?.cancel()
        return scope.launch {
            // Read again whenever what the answer depends on moves: the hub
            // coming back (a read made while it was down failed), or a
            // reconnect to a hub that now lists `operator_status`. The first
            // value is the read below, so it is skipped.
            launch {
                combine(fleet.capabilities, fleet.status) { caps, status -> caps.operatorStatus to (status is ConnectionStatus.Connected) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { (_, up) -> if (up) readStatus() }
            }
            readStatus()
            while (isActive && (fleet.capabilities.value.confirms || fleet.capabilities.value.handoffs)) {
                // Offline or stopped (the app went to the background): no call
                // can land, so the tick waits for the next one (review r16).
                if (fleet.status.value is ConnectionStatus.Connected) {
                    if (fleet.capabilities.value.confirms) readConfirms()
                    if (fleet.capabilities.value.handoffs) readHandoffs()
                }
                delay(pollMs)
            }
        }.also { loop = it }
    }

    fun detach() {
        loop?.cancel()
        loop = null
    }

    /** Re-read Control's state, as after a reconnect or a pull — and the Retry under a failed read. */
    fun refresh(): Job = scope.launch {
        local.update { it.copy(error = null) }
        readStatus()
        if (fleet.capabilities.value.confirms) readConfirms()
        if (fleet.capabilities.value.handoffs) readHandoffs()
    }

    /** Wake Control: the one tap that may start its session. */
    fun wake(): Job? {
        if (!state.value.canWake || local.value.waking) return null
        local.update { it.copy(waking = true, error = null) }
        return scope.launch {
            try {
                val row = agent.ensureOperator()
                local.update { it.copy(waking = false, woken = row.id) }
                readStatus()
            } catch (e: CancellationException) {
                local.update { it.copy(waking = false) }
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(waking = false, error = friendly(t)) }
            }
        }
    }

    /** Approve or deny one of Control's calls; only on a tap, never pre-selected. */
    fun answer(nonce: String, approved: Boolean): Job? {
        val s = state.value
        if (!s.canConfirm || nonce in s.answering || s.confirms.none { it.nonce == nonce }) return null
        local.update { it.copy(answering = it.answering + nonce, error = null) }
        return scope.launch {
            try {
                val recorded = actions.answer(nonce, approved)
                // Gone either way: answered now, or already answered or expired elsewhere.
                local.update { l -> l.copy(confirms = l.confirms.filterNot { it.nonce == nonce }, answering = l.answering - nonce) }
                // The hub keeps the FIRST answer. False means the desktop got
                // there first or the request expired: this tap counted for
                // nothing, and the person must not believe it did.
                if (!recorded) local.update { it.copy(error = notRecorded(approved)) }
            } catch (e: CancellationException) {
                local.update { it.copy(answering = it.answering - nonce) }
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(answering = it.answering - nonce, error = friendly(t)) }
            }
        }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    private suspend fun readStatus() {
        if (!fleet.capabilities.value.operatorStatus) return
        try {
            val status = actions.status()
            local.update { it.copy(known = true, status = status, woken = if (status.ready) null else it.woken, statusFailed = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(known = true, error = friendly(t), statusFailed = true) }
        }
    }

    private suspend fun readConfirms() {
        try {
            val confirms = actions.confirms()
            local.update { it.copy(confirms = confirms) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Read again on the next tick; a confirm that waits is not lost.
        }
    }

    private suspend fun readHandoffs() {
        try {
            val handoffs = actions.handoffs(HANDOFFS_SHOWN)
            local.update { it.copy(handoffs = handoffs) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Keep what is shown; the next tick reads again.
        }
    }

    companion object {
        const val CONFIRM_POLL_MS = 4_000L

        /** How many receipts Control reads, and how many chips it draws. */
        const val HANDOFFS_SHOWN = 20
        const val CHIPS_SHOWN = 3
    }
}

const val CONTROL_VIEW_TAG = "control.view."
const val CONTROL_WAKE_TAG = "control.wake"
const val CONFIRM_CARD_TAG = "control.confirm."
/** What a confirm answer the hub refused says: answered elsewhere first, or expired. */
internal fun notRecorded(approved: Boolean): Friendly = Friendly(
    "Already answered or expired",
    "Your ${if (approved) "approval" else "denial"} was not recorded — the request was answered on another device first, or it had expired.",
    isError = false,
)

const val CONFIRM_APPROVE_TAG = "control.confirm.approve."
const val CONFIRM_DENY_TAG = "control.confirm.deny."

/** The header of the Control tab: "Control · N need you", and its views below. */
@Composable
fun ControlHeader(subtitle: String?, views: @Composable () -> Unit) {
    ScreenHeader(
        title = "Control",
        subtitle = subtitle,
        modifier = Modifier.semantics { heading() },
        below = { views() },
    )
}

/** Chat, Sessions N, Missions N: the board's view tabs. Chat is this tab; the others open their screens. */
@Composable
fun ControlViews(
    sessions: Int?,
    missions: Int?,
    onSessions: () -> Unit,
    onMissions: (() -> Unit)?,
    pullRequests: Int? = null,
    onPullRequests: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ViewPill("Chat", selected = true, tag = ControlView.Chat) {}
        ViewPill(listOfNotNull("Sessions", sessions?.toString()).joinToString(" "), selected = false, tag = ControlView.Sessions, onClick = onSessions)
        if (onMissions != null) {
            ViewPill(listOfNotNull("Missions", missions?.toString()).joinToString(" "), selected = false, tag = ControlView.Missions, onClick = onMissions)
        }
        // MobileControl: "Chat | Sessions 22 | Missions 3 | Pull requests 5" (review r09 B8).
        if (onPullRequests != null) {
            ViewPill(listOfNotNull("Pull requests", pullRequests?.toString()).joinToString(" "), selected = false, tag = ControlView.PullRequests, onClick = onPullRequests)
        }
    }
}

@Composable
private fun ViewPill(label: String, selected: Boolean, tag: ControlView, onClick: () -> Unit) {
    val o = Fleet.colors
    Surface(
        color = if (selected) o.accent.copy(alpha = 0.16f) else o.bgPane,
        border = BorderStroke(1.dp, if (selected) o.accent else o.controlBorder),
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .heightIn(min = 32.dp)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .testTag(CONTROL_VIEW_TAG + tag.name),
    ) {
        Text(label, style = Fleet.type.textSm, color = if (selected) o.fg else o.fg2, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

/**
 * Control's calls that wait on this device's yes, oldest first: what it wants
 * done in words, the exact call in mono, and Approve or Deny. Neither is
 * pre-selected; a card answered elsewhere goes on the next read.
 */
@Composable
fun ConfirmCards(state: ControlUiState, onAnswer: (String, Boolean) -> Unit) {
    if (state.confirms.isEmpty()) return
    val o = Fleet.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in state.confirms) {
            val busy = c.nonce in state.answering
            Surface(
                color = o.bgPane,
                border = BorderStroke(1.dp, o.statusWaiting),
                shape = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp),
                modifier = Modifier.fillMaxWidth().testTag(CONFIRM_CARD_TAG + c.nonce),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (c.operator) "Control asks" else "${c.caller.ifBlank { "A caller" }} asks",
                        style = Fleet.type.textSm,
                        color = o.statusWaiting,
                    )
                    Text(c.summary.ifBlank { c.tool }, style = Fleet.type.textMd, color = o.fg)
                    Text(
                        c.tool,
                        style = Fleet.type.code,
                        color = o.fgMuted,
                        modifier = Modifier.background(o.bgSunk, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { onAnswer(c.nonce, false) },
                            enabled = !busy,
                            modifier = Modifier.testTag(CONFIRM_DENY_TAG + c.nonce),
                        ) { Text("Deny") }
                        OutlinedButton(
                            onClick = { onAnswer(c.nonce, true) },
                            enabled = !busy,
                            modifier = Modifier.testTag(CONFIRM_APPROVE_TAG + c.nonce),
                        ) { Text(if (busy) "Sending…" else "Approve") }
                    }
                }
            }
        }
    }
}

/**
 * The Control tab before there is a conversation to show: why, and Wake
 * Control when waking can help. A hub that does not offer the coordinator to
 * this pairing says so instead.
 */
@Composable
fun ControlWaiting(
    state: ControlUiState,
    subtitle: String?,
    views: @Composable () -> Unit,
    onWake: () -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    onDismissError: () -> Unit,
    below: @Composable () -> Unit = {},
    /** Read Control's state again after a failed read. */
    onRetry: () -> Unit = {},
) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    Column(modifier = Modifier.fillMaxSize()) {
        ControlHeader(subtitle, views)
        ErrorBanner(state.error, onDismiss = onDismissError, onRetry = onRetry.takeIf { state.statusFailed && state.connected })
        Column(modifier = Modifier.padding(horizontal = gutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                controlWaitingLine(state),
                style = Fleet.type.textMd,
                color = o.fg2,
            )
            if (state.available && state.connected && state.statusFailed && !state.waking) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp),
                ) { Text("Retry") }
            }
            if (state.canWake && state.known) {
                Button(
                    onClick = onWake,
                    enabled = !state.waking,
                    modifier = Modifier.heightIn(min = OrbitTokens.spacing("touch-min").dp).testTag(CONTROL_WAKE_TAG),
                ) { Text(if (state.waking) "Waking…" else "Wake Control") }
            }
        }
        ConfirmCards(state, onAnswer)
        Spacer(Modifier.height(8.dp))
        below()
    }
}

/** What the Control tab puts on the conversation: its header, and what goes above the composer. */
class ControlChrome(
    val header: @Composable () -> Unit,
    val aboveComposer: @Composable () -> Unit,
)

/**
 * One handoff as a chip: what was handed on, to what, and the target's state
 * now in the manual's five words (working, waiting, done, failed, idle), or
 * null once the target is gone. [sessionId] is set when a tap opens it.
 */
data class HandoffChip(
    val id: Long,
    val label: String,
    val state: String?,
    val sessionId: Long? = null,
)

/** A session's live state as one of the five. */
fun handoffSessionState(row: SessionRow): String = when {
    row.stuckKind != null -> "failed"
    row.claudeStatus == "working" -> "working"
    row.claudeStatus == "blocked" || row.pendingForm != null -> "waiting"
    row.claudeStatus == "failed" -> "failed"
    row.claudeStatus == "completed" -> "done"
    else -> "idle"
}

/** A mission's or a task's state as one of the five. */
fun handoffWorkState(state: String?): String = when (state) {
    "active", "in_progress", "running" -> "working"
    "completed", "done" -> "done"
    "failed", "cancelled" -> "failed"
    "blocked" -> "waiting"
    else -> "idle"
}

/** The newest [ControlViewModel.CHIPS_SHOWN] receipts as chips, oldest first (the order a transcript reads). */
fun handoffChips(handoffs: List<ControlHandoff>, rows: List<SessionRow>): List<HandoffChip> =
    handoffs.take(ControlViewModel.CHIPS_SHOWN).reversed().map { h -> handoffChip(h, rows) }

fun handoffChip(h: ControlHandoff, rows: List<SessionRow>): HandoffChip = when (h.kind) {
    "session" -> {
        val row = rows.firstOrNull { it.id == h.sessionId }
        if (row == null) {
            HandoffChip(h.id, "Sent to ${h.preview ?: "a session"} · ended", null)
        } else {
            HandoffChip(h.id, "Sent to ${row.displayName}", handoffSessionState(row), row.id)
        }
    }
    "mission" -> HandoffChip(
        h.id,
        "Mission ${h.missionName ?: "#${h.missionId}"}",
        h.missionName?.let { handoffWorkState(h.missionState) },
    )
    "task" -> HandoffChip(h.id, "Task ${h.item?.title ?: "(gone)"}", h.item?.let { handoffWorkState(it.status) })
    "tree" -> {
        val waiting = h.items.count { it.proposalState == "proposed" }
        val parent = h.item?.title?.let { " under $it" }.orEmpty()
        HandoffChip(
            h.id,
            "Proposed ${h.items.size} subtasks$parent" + if (waiting > 0) " · $waiting to decide on the desktop" else "",
            if (waiting > 0) "waiting" else "done",
        )
    }
    else -> HandoffChip(h.id, h.preview ?: h.tool, null)
}

const val HANDOFF_CHIP_TAG = "control.handoff."

/** Control's recent handoffs, above its confirms: "Sent to api · Working". A session's chip opens it. */
@Composable
fun HandoffChips(chips: List<HandoffChip>, onOpenSession: (Long) -> Unit) {
    if (chips.isEmpty()) return
    val o = Fleet.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (c in chips) {
            val tint = when (c.state) {
                "working" -> o.statusWorking
                "waiting" -> o.statusWaiting
                "done" -> o.statusDone
                "failed" -> o.statusFailed
                else -> o.statusIdle
            }
            Surface(
                color = o.bgPane,
                border = BorderStroke(1.dp, o.controlBorder),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .then(if (c.sessionId != null) Modifier.clickable(role = Role.Button) { onOpenSession(c.sessionId) } else Modifier)
                    .testTag(HANDOFF_CHIP_TAG + c.id),
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("↗ ", style = Fleet.type.textSm, color = o.fgMuted)
                    Text(c.label, style = Fleet.type.textSm, color = o.fg, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    c.state?.let {
                        Text(" · ", style = Fleet.type.textSm, color = o.fgMuted)
                        Text(it.replaceFirstChar { ch -> ch.uppercase() }, style = Fleet.type.textSm, color = tint)
                    }
                }
            }
        }
    }
}
