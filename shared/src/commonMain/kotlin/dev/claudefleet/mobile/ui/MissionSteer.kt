package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.SessionRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * "Steer this mission" (Work → Missions as a Thread room, claude-fleet
 * #921): the mission's composer hands the text to Control's agent, with a
 * prefix naming the mission, as one prompt. Control does the steering; the
 * phone only delivers. A port of the desktop's `mission_room.ts` steer.
 */

/** What rides in front of a steer, so Control knows which mission: the desktop's `steerPrefix`. */
fun steerPrefix(m: Mission): String = "About mission #${m.id} \"${m.name}\":"

/** The prompt a steer sends. */
fun steerBody(m: Mission, text: String): String = "${steerPrefix(m)}\n\n${text.trim()}"

/** Why the composer may not send now, in words; null when it may. */
fun steerBlocked(operator: SessionRow?, connected: Boolean, canWrite: Boolean): String? = when {
    !canWrite -> "This phone is paired read-only, so it cannot steer."
    !connected -> "Not connected to the hub. Steer once it answers."
    operator == null -> "Control's agent isn't running, so there is no one to steer through. Open Control to start it."
    operator.lostAt != null || operator.status == "ghost" -> "Control's agent was lost. Open Control to bring it back."
    else -> null
}

/**
 * Why Send waits now, or null (review w04-12): a steer is a prompt in
 * Control's REPL, so it must not be typed into Control's turn. A hub that
 * keeps prompts ([hubQueues], `queue_prompt`) takes it into Control's queue,
 * behind the lines already waiting there, so nothing waits; an older hub
 * would type it at once, so Send waits while Control is mid-turn. The
 * desktop's `steerBusy` words.
 */
fun steerBusy(operator: SessionRow?, hubQueues: Boolean): String? =
    if (!hubQueues && operator?.claudeStatus == "working") {
        "Control is mid-turn. Send opens when it finishes, so this is not typed into its turn."
    } else {
        null
    }

/** One steer sent: the room's receipt line. */
data class SteerReceipt(val missionId: Long, val text: String, val at: Long)

data class SteerState(
    val sending: Long? = null,
    val receipts: List<SteerReceipt> = emptyList(),
    val error: Friendly? = null,
    /**
     * Steers Control took, by mission, counted for good: [receipts] is capped,
     * so its size stops growing and cannot tell the composer a send went
     * through (review w21-15).
     */
    val delivered: Map<Long, Int> = emptyMap(),
)

/** What the mission detail draws its composer from. */
data class MissionSteerUi(
    /** Why it may not send; null when it may. */
    val blocked: String?,
    val sending: Boolean,
    /** This mission's steers, newest last. */
    val sent: List<SteerReceipt>,
    val error: Friendly?,
    val onSend: (String) -> Unit,
    val onDismissError: () -> Unit,
    /** How many of this mission's steers Control took ([SteerState.delivered]); the composer clears when it grows. */
    val delivered: Int = 0,
    /** Why Send waits for now ([steerBusy]); the box stays open. */
    val busy: String? = null,
)

/**
 * Steers sent from the phone. [operatorId] answers Control's session now
 * (the Control tab's own reading of `operator_status`).
 */
class MissionSteer(
    private val fleet: FleetState,
    private val actions: SessionActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    private val operatorId: () -> Long?,
) {
    private val local = MutableStateFlow(SteerState())
    val state: StateFlow<SteerState> = local.asStateFlow()

    /** Control's row, when its session is known and listed. */
    fun operator(rows: List<SessionRow>): SessionRow? = operatorId()?.let { id -> rows.firstOrNull { it.id == id } }

    fun blocked(rows: List<SessionRow>, status: ConnectionStatus): String? =
        steerBlocked(operator(rows), status is ConnectionStatus.Connected, canWrite)

    /** [steerBusy] for Control's row in [rows]. */
    fun busy(rows: List<SessionRow>): String? = steerBusy(operator(rows), fleet.capabilities.value.sendLater)

    fun send(m: Mission, text: String): Job? {
        if (text.isBlank() || local.value.sending != null) return null
        if (blocked(fleet.sessions.value, fleet.status.value) != null) return null
        if (busy(fleet.sessions.value) != null) return null
        val op = operator(fleet.sessions.value) ?: return null
        val caps = fleet.capabilities.value
        local.update { it.copy(sending = m.id, error = null) }
        return scope.launch {
            try {
                // Into Control's queue where the hub keeps one, as Control's
                // own composer sends: typed now when Control is idle, else
                // after its turn and behind what already waits (review w04-12).
                if (caps.sendLater) {
                    actions.queuePrompt(op.id, steerBody(m, text))
                } else {
                    actions.sendPrompt(op.id, steerBody(m, text))
                }
                // Control follows the mission from now on (review w04-11), so
                // its feed says what the mission does next. Best effort: the
                // steer went already, and an older hub or a device that is
                // not the owner's is refused.
                if (caps.controlJournal) {
                    try {
                        actions.followMission(m.id, text.trim())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
                local.update {
                    it.copy(
                        sending = null,
                        receipts = (it.receipts + SteerReceipt(m.id, text.trim(), epochSeconds())).takeLast(MAX_RECEIPTS),
                        delivered = it.delivered + (m.id to (it.delivered[m.id] ?: 0) + 1),
                    )
                }
            } catch (e: CancellationException) {
                local.update { it.copy(sending = null) }
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(sending = null, error = friendly(t)) }
            }
        }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    private companion object {
        const val MAX_RECEIPTS = 20
    }
}
