package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AgentActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.net.HubCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AgentUiState(
    /** The hub serves `ensure_operator` to this token: the Sessions header offers **Agent**. */
    val available: Boolean = false,
    /** The hub is finding or starting the agent — it can take a while the first time. */
    val waking: Boolean = false,
    val error: Friendly? = null,
)

/**
 * The way into the fleet's agent from the phone: one call, then the agent's
 * own session on the ordinary Session screen — its conversation, its prompt
 * box, its quick replies. The desktop's agent sheet is that same session in a
 * smaller frame, so the phone needs no second conversation view.
 *
 * Offered only when the hub lists `ensure_operator` for this token (an older
 * hub does not, and the hub hides it from nobody else) **and** the credential
 * can write: waking the agent may start a session, which a `readonly` pairing
 * may not.
 */
class AgentViewModel(
    private val fleet: FleetState,
    private val actions: AgentActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    /** Show the agent's session. */
    private val onOpenSession: (Long) -> Unit,
) {
    private data class Local(val waking: Boolean = false, val error: Friendly? = null)

    private val local = MutableStateFlow(Local())

    val state: StateFlow<AgentUiState> =
        combine(fleet.capabilities, local) { caps, l -> assemble(caps, l) }
            .stateIn(scope, SharingStarted.Eagerly, assemble(fleet.capabilities.value, local.value))

    /** Wake the agent (a no-op while one wake is in flight) and open its session. */
    fun open(): Job? {
        if (!state.value.available || local.value.waking) return null
        local.update { Local(waking = true) }
        return scope.launch {
            try {
                val row = actions.ensureOperator()
                local.update { Local() }
                onOpenSession(row.id)
            } catch (e: CancellationException) {
                local.update { Local() }
                throw e
            } catch (t: Throwable) {
                local.update { Local(error = friendly(t)) }
            }
        }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    private fun assemble(caps: HubCapabilities, l: Local): AgentUiState =
        if (!canWrite || !caps.agent) AgentUiState()
        else AgentUiState(available = true, waking = l.waking, error = l.error)
}
