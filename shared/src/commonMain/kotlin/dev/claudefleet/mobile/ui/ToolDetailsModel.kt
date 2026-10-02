package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.model.ToolDetail
import dev.claudefleet.mobile.net.HubError
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

    /**
     * The read failed, carrying WHY.
     *
     * Every `Throwable` used to collapse into one carrier-less value, so the
     * screen could not tell a timeout from a refusal: it offered Retry for
     * causes that can never succeed, and each attempt is a transcript grep over
     * SSH on the session's own host.
     */
    data class Failed(val error: Throwable?) : ToolDetailLoad {
        /** What to show instead of a bare "couldn't load", when the hub said. */
        val said: String? get() = when (val e = error) {
            is HubError.Tool -> e.message
            is HubError.Forbidden -> e.message
            else -> null
        }

        /**
         * Whether reading again could answer differently. A refusal with a
         * terminal code cannot: the transcript has no such call, or the
         * request itself is wrong. Mirrors the desktop's `loadRetryable`.
         */
        val retryable: Boolean get() = when (val e = error) {
            is HubError.Tool -> e.code !in TERMINAL_CODES
            is HubError.Forbidden -> false
            else -> true
        }
    }

    companion object {
        /** Refusals that will read the same way however often they are asked. */
        internal val TERMINAL_CODES = setOf("E_NOTFOUND", "E_NO_TRANSCRIPT", "E_INVALID", "E_INVALID_STATE")
    }
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
     * that one answer is read again rather than kept.
     *
     * A FAILURE is not retried on its own. Only [force] — the row's Retry —
     * asks again. The row opens with a `LaunchedEffect`, and a keyed
     * `LazyColumn` re-runs it every time the row scrolls back into view, so
     * "Failed → start again" meant each scroll past a failing call fired
     * another transcript grep over SSH, against the method's own promise and
     * its test.
     */
    fun request(toolUseId: String, done: Boolean = true, force: Boolean = false) {
        if (!fleet.capabilities.value.toolDetail) return
        var start = false
        _states.update { held ->
            val current = held[toolUseId]
            val stale = current is ToolDetailLoad.Loaded && current.detail.result == null && done
            val retry = force && current is ToolDetailLoad.Failed && current.retryable
            if (current == null || stale || retry) {
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
                ToolDetailLoad.Loaded(actions.toolDetail(sessionId, toolUseId))
            } catch (e: CancellationException) {
                _states.update { it - toolUseId }
                throw e
            } catch (t: Throwable) {
                ToolDetailLoad.Failed(t)
            }
            _states.update { it + (toolUseId to next) }
        }
    }
}
