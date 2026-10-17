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

/** One steer sent: the room's receipt line. */
data class SteerReceipt(val missionId: Long, val text: String, val at: Long)

data class SteerState(
    val sending: Long? = null,
    val receipts: List<SteerReceipt> = emptyList(),
    val error: Friendly? = null,
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

    fun send(m: Mission, text: String): Job? {
        if (text.isBlank() || local.value.sending != null) return null
        if (blocked(fleet.sessions.value, fleet.status.value) != null) return null
        val op = operator(fleet.sessions.value) ?: return null
        local.update { it.copy(sending = m.id, error = null) }
        return scope.launch {
            try {
                actions.sendPrompt(op.id, steerBody(m, text))
                local.update {
                    it.copy(sending = null, receipts = (it.receipts + SteerReceipt(m.id, text.trim(), epochSeconds())).takeLast(MAX_RECEIPTS))
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
