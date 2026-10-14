package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.BuiltInAgentActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.AgentAction
import dev.claudefleet.mobile.model.AgentRule
import dev.claudefleet.mobile.net.HubError
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

/*
 * The agents' actions in the Inbox (claude-fleet agents design step 5): the
 * reviews the PR reviewer waits on a person to post, each with Approve and
 * Reject. The hub serves `agents` only to the owner's own device, and only a
 * trusted full one may answer; a hub without the tool (or one that refuses
 * this device) shows nothing.
 */

data class AgentActionsUiState(
    /** The hub serves `agents` to this device. */
    val available: Boolean = false,
    /** This device may approve or reject (a write token; the hub still checks trust). */
    val canAnswer: Boolean = false,
    val pending: List<AgentAction> = emptyList(),
    /** The standing rules: built-in agents' and fleet agents' instances (Automation › Agents). */
    val rules: List<AgentRule> = emptyList(),
    /** Actions with a press on the wire. */
    val busy: Set<Long> = emptySet(),
    /** What the last press did, in words, by action. */
    val notices: Map<Long, String> = emptyMap(),
    /** What the last Revoke did, in words. */
    val ruleNotice: String? = null,
)

class AgentActionsViewModel(
    private val fleet: FleetState,
    private val actions: BuiltInAgentActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val pending: List<AgentAction> = emptyList(),
        val rules: List<AgentRule> = emptyList(),
        val ruleNotice: String? = null,
        val busy: Set<Long> = emptySet(),
        val notices: Map<Long, String> = emptyMap(),
        /** The hub refused this device the tool: stop asking this connection. */
        val refused: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<AgentActionsUiState> = combine(local, fleet.capabilities) { l, caps ->
        val available = caps.has(TOOL, "status") && !l.refused
        AgentActionsUiState(
            available = available,
            canAnswer = available && canWrite && caps.has(TOOL, "approve"),
            pending = if (available) l.pending else emptyList(),
            rules = if (available) l.rules else emptyList(),
            busy = l.busy,
            notices = l.notices,
            ruleNotice = l.ruleNotice,
        )
    }.stateIn(scope, SharingStarted.Eagerly, AgentActionsUiState())

    /** Read the waiting actions again (the Inbox does on show). */
    fun refresh(): Job? {
        if (!state.value.available) return null
        return scope.launch {
            try {
                val s = actions.status()
                local.update { it.copy(pending = s.pending, rules = s.rules) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HubError.Tool) {
                if (e.code == "E_FORBIDDEN") local.update { it.copy(refused = true, pending = emptyList()) }
            } catch (_: Throwable) {
                // Keep the last list: a dropped connection is not "nothing waits".
            }
        }
    }

    /** End a rule (a fleet agent's instance); the list drops it. */
    fun revoke(r: AgentRule): Job? {
        if (!state.value.canAnswer) return null
        return scope.launch {
            val notice = try {
                actions.revoke(r.id)
                local.update { l -> l.copy(rules = l.rules.filterNot { it.id == r.id }) }
                "${r.scope} revoked."
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                friendly(t).body
            }
            local.update { it.copy(ruleNotice = notice) }
        }
    }

    fun approve(a: AgentAction): Job? = answer(a, true)

    fun reject(a: AgentAction): Job? = answer(a, false)

    private fun answer(a: AgentAction, approve: Boolean): Job? {
        val s = state.value
        if (!s.canAnswer || a.id in s.busy) return null
        local.update { it.copy(busy = it.busy + a.id, notices = it.notices - a.id) }
        return scope.launch {
            val notice = try {
                val done = actions.decide(a.id, approve)
                local.update { l -> l.copy(pending = l.pending.filterNot { it.id == done.id }) }
                if (approve) "Approved: it goes out on the hub's next pass." else "Rejected."
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                friendly(t).body
            }
            local.update { it.copy(busy = it.busy - a.id, notices = it.notices + (a.id to notice)) }
        }
    }

    companion object {
        const val TOOL = "agents"
    }
}
