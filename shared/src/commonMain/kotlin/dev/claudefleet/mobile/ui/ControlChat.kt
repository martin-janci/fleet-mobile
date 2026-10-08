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
import dev.claudefleet.mobile.data.ControlActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.ConfirmRequest
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

/*
 * Control on the phone (redesign 9.8 and 14.7, the MobileControl board): the
 * fleet's coordinator as the Control tab itself rather than a row that opens
 * its session. The tab is Control's conversation under a "Control · N need
 * you" header with its views, and the calls Control waits on a person's yes
 * for (9.2's confirms) as cards above the composer, Approve and Deny, neither
 * pre-selected. New layout only; the Classic bar keeps the agent button.
 */

/** Control's views across the top of the tab. Sessions and Missions open their own screens. */
enum class ControlView { Chat, Sessions, Missions }

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
    val error: Friendly? = null,
) {
    /** Waking may help: nothing runs, or what ran is gone or lost its tools. */
    val canWake: Boolean get() = available && sessionId == null && (blocked == null || blocked in WAKEABLE)
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
        else -> "Control cannot take a message right now ($blocked)."
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
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())
    private var loop: Job? = null

    val state: StateFlow<ControlUiState> =
        combine(local, fleet.capabilities) { l, caps -> assemble(l, caps) }
            .stateIn(scope, SharingStarted.Eagerly, assemble(local.value, fleet.capabilities.value))

    private fun assemble(l: Local, caps: HubCapabilities): ControlUiState {
        val available = canWrite && caps.agent
        val status = l.status
        val sessionId = status?.session?.id?.takeIf { status.ready } ?: l.woken
        return ControlUiState(
            available = available,
            known = l.known || !caps.operatorStatus,
            sessionId = if (available) sessionId else null,
            blocked = status?.blocked?.takeIf { sessionId == null },
            host = status?.host,
            waking = l.waking,
            canConfirm = caps.confirms,
            confirms = if (caps.confirms) l.confirms else emptyList(),
            answering = l.answering,
            error = l.error,
        )
    }

    /** Follow Control while its tab shows. */
    fun attach(): Job {
        loop?.cancel()
        return scope.launch {
            readStatus()
            while (isActive && fleet.capabilities.value.confirms) {
                readConfirms()
                delay(pollMs)
            }
        }.also { loop = it }
    }

    fun detach() {
        loop?.cancel()
        loop = null
    }

    /** Re-read Control's state, as after a reconnect or a pull. */
    fun refresh(): Job = scope.launch {
        readStatus()
        if (fleet.capabilities.value.confirms) readConfirms()
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
                actions.answer(nonce, approved)
                // Gone either way: answered now, or already answered or expired elsewhere.
                local.update { l -> l.copy(confirms = l.confirms.filterNot { it.nonce == nonce }, answering = l.answering - nonce) }
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
            local.update { it.copy(known = true, status = status, woken = if (status.ready) null else it.woken) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(known = true, error = friendly(t)) }
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

    companion object {
        const val CONFIRM_POLL_MS = 4_000L
    }
}

const val CONTROL_VIEW_TAG = "control.view."
const val CONTROL_WAKE_TAG = "control.wake"
const val CONFIRM_CARD_TAG = "control.confirm."
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
fun ControlViews(sessions: Int?, missions: Int?, onSessions: () -> Unit, onMissions: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ViewPill("Chat", selected = true, tag = ControlView.Chat) {}
        ViewPill(listOfNotNull("Sessions", sessions?.toString()).joinToString(" "), selected = false, tag = ControlView.Sessions, onClick = onSessions)
        if (onMissions != null) {
            ViewPill(listOfNotNull("Missions", missions?.toString()).joinToString(" "), selected = false, tag = ControlView.Missions, onClick = onMissions)
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
) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    Column(modifier = Modifier.fillMaxSize()) {
        ControlHeader(subtitle, views)
        ErrorBanner(state.error, onDismiss = onDismissError)
        Column(modifier = Modifier.padding(horizontal = gutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when {
                    !state.available -> "The hub does not offer Control to this device."
                    !state.known -> "Asking the hub about Control…"
                    state.waking -> "Waking Control…"
                    else -> controlBlockedLine(state.blocked, state.host)
                },
                style = Fleet.type.textMd,
                color = o.fg2,
            )
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

/** What the Control tab puts on the conversation: its header, and what goes above the composer. */
class ControlChrome(
    val header: @Composable () -> Unit,
    val aboveComposer: @Composable () -> Unit,
)
