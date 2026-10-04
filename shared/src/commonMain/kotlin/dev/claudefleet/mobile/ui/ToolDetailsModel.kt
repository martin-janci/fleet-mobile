package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.model.ToolDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where one tool call's expanded detail is. */
sealed interface ToolDetailLoad {
    data object Loading : ToolDetailLoad
    data class Loaded(val detail: ToolDetail) : ToolDetailLoad
    data object Failed : ToolDetailLoad
}

/**
 * The expanded tool rows' details for one session screen: read lazily, the
 * first time a row is opened, and kept per tool id for the life of the screen.
 *
 * A conversation read carries one-liners only, so this is the one place
 * `session_tool_detail` is called from. It is asked only when the hub lists
 * the tool ([dev.claudefleet.mobile.net.HubCapabilities.toolDetail]) — an
 * older hub's rows are simply not expandable — and it is readonly on the hub,
 * so a readonly token may use it.
 *
 * Kept apart from [SessionViewModel] on purpose: nothing here touches the
 * conversation's read queue, and a detail read must never wait behind (or hold
 * up) a conversation refetch.
 */
class ToolDetailsModel(
    private val sessionId: Long,
    private val fleet: FleetState,
    private val actions: SessionActions,
    private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<String, ToolDetailLoad>>(emptyMap())
    val states: StateFlow<Map<String, ToolDetailLoad>> = _states.asStateFlow()

    /**
     * Read [toolUseId]'s detail unless it is already held or on its way.
     *
     * [done] is what the row knows now: a detail read while the call was
     * still running has no result, so once the row says the call finished,
     * that one answer is read again rather than kept. A failure is retried
     * only when asked again (the row's Retry).
     */
    /**
     * The earlier conversation on screen, or null for the current one: a tool
     * row there is looked up in that conversation's transcript.
     */
    var claudeSessionId: String? = null

    fun request(toolUseId: String, done: Boolean = true) {
        if (!fleet.capabilities.value.toolDetail) return
        var start = false
        _states.update { held ->
            val current = held[toolUseId]
            val stale = current is ToolDetailLoad.Loaded && current.detail.result == null && done
            if (current == null || current is ToolDetailLoad.Failed || stale) {
                start = true
                held + (toolUseId to ToolDetailLoad.Loading)
            } else {
                start = false
                held
            }
        }
        if (!start) return
        scope.launch {
            val next = try {
                ToolDetailLoad.Loaded(actions.toolDetail(sessionId, toolUseId, claudeSessionId))
            } catch (e: CancellationException) {
                _states.update { it - toolUseId }
                throw e
            } catch (t: Throwable) {
                ToolDetailLoad.Failed
            }
            _states.update { it + (toolUseId to next) }
        }
    }
}
