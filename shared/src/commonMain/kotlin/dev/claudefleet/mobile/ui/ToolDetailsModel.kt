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
     * Tool ids whose row said the call had FINISHED while a read of it was
     * still in flight.
     *
     * A row asks as soon as it opens, which is usually while the call is
     * still running, and asks again with `done = true` when the result
     * arrives — but the second ask used to be dropped, because a `Loading`
     * entry is already "on its way". So the in-flight answer, which has no
     * `result`, was stored as the final one and the card said "running…"
     * under a row that had stopped spinning: the one state nothing clears,
     * since the row's `LaunchedEffect` will not re-fire for the same
     * `tool.done`. Remembering the wanted `done` here lets the read that is
     * already going finish and then be taken again, once.
     *
     * A [MutableStateFlow] rather than a `mutableSetOf`: this model's scope is
     * `Dispatchers.Default`, so the row's `request` and the read's own
     * continuation are on different threads, and `update` is the only
     * compare-and-set a common source set has.
     */
    private val wantDone = MutableStateFlow<Set<String>>(emptySet())

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
        var missedDone = false
        _states.update { held ->
            val current = held[toolUseId]
            val stale = current is ToolDetailLoad.Loaded && current.detail.result == null && done
            val retry = force && current is ToolDetailLoad.Failed && current.retryable
            missedDone = done && current is ToolDetailLoad.Loading
            if (current == null || stale || retry) {
                start = true
                held + (toolUseId to ToolDetailLoad.Loading)
            } else {
                start = false
                held
            }
        }
        if (missedDone) wantDone.update { it + toolUseId }
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
            // The `done` that arrived while this read was in flight. Asking
            // again here rather than at the flip is what makes it one extra
            // read and not a loop: `wantDone` is emptied by taking it, and the
            // second answer either carries a result (so `stale` is false) or
            // the call really produced none.
            var missed = false
            wantDone.update { held ->
                missed = toolUseId in held
                held - toolUseId
            }
            if (missed && next is ToolDetailLoad.Loaded && next.detail.result == null) {
                request(toolUseId, done = true)
            }
        }
    }
}
