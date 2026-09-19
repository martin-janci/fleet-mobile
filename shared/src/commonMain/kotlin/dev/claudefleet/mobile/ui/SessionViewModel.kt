@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.appending
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/** One session's screen: its row, its conversation, and what is being typed. */
data class SessionUiState(
    /**
     * The live row, or null when the fleet no longer has this session — killed
     * from the desktop, say. Null is not "still loading": it is the bar going
     * empty rather than going stale.
     */
    val session: SessionRow? = null,
    val conversation: Conversation = Conversation(),
    /** True once a read has answered, even with nothing in it. */
    val loaded: Boolean = false,
    val draft: String = "",
    val loading: Boolean = false,
    val sending: Boolean = false,
    /** True when this device's credential is `readonly` and may not send. */
    val readOnly: Boolean = false,
    val error: String? = null,
) {
    /**
     * Whether the send button does anything. Blank drafts are not prompts, a
     * second prompt while the first is in flight would race the hub's turn
     * counter, a dead session has no REPL to type into, and a readonly token is
     * refused `send_prompt` by the hub.
     */
    val canSend: Boolean
        get() = !sending && !readOnly && session != null && draft.isNotBlank()
}

/**
 * One session: the conversation newest at the bottom, and a box to answer it.
 *
 * The row in the bar comes from the live fleet picture, so the status and the
 * one-line activity follow the event stream with no polling. The conversation
 * is fetched by a tool call, `session_conversation`, but it is not merely
 * polled either: this class also refetches on [FleetState.sessionChanges]
 * whenever the hub reports a row change for *this* session — a reply landing
 * updates `claude_status` and `current_activity` on the same row the bar
 * already follows, so the same signal that moves the bar is the cue to pull
 * the reply in. That subscription is debounced ([SESSION_EVENT_DEBOUNCE]) so a
 * burst of frames during one turn costs one read, and it runs for as long as
 * this view model does: the screen owns [scope], so closing the screen stops
 * it the same way it stops everything else here.
 *
 * A refresh — pull-to-refresh, after a send, or event-triggered — **appends**.
 * The hub answers a rolling window of the tail, so a second read overlaps the
 * first rather than continuing it; see [dev.claudefleet.mobile.model.appending].
 * Those four callers never race each other's hub call, either: [fetchLock]
 * serializes it, so results still apply in the order they were asked for.
 *
 * @param canSendPrompts false for a `readonly` credential. `send_prompt` is not
 *   in the hub's readonly allow-list (`READONLY_TOOLS` in `mcp/guard.rs`), so a
 *   readonly client would simply be refused — and the app's rule is that it only
 *   ever calls tools its token may use, rather than finding out from an error.
 */
class SessionViewModel(
    private val sessionId: Long,
    private val fleet: FleetState,
    private val actions: SessionActions,
    private val scope: CoroutineScope,
    canSendPrompts: Boolean = true,
) {
    /**
     * The screen state this class owns, as opposed to what the fleet owns.
     *
     * Every mutation goes through `MutableStateFlow.update {}` rather than
     * `local.value = local.value.copy(...)` — review N5. The latter is a
     * read-modify-write, and two coroutines in this scope really can interleave
     * across one: a pull-to-refresh while a send's follow-up read is in flight,
     * or a double tap, would lose one merge or clear `loading` while the other
     * call was still running.
     */
    private data class Local(
        val conversation: Conversation = Conversation(),
        val loaded: Boolean = false,
        val draft: String = "",
        val loading: Boolean = false,
        val sending: Boolean = false,
        val error: String? = null,
    )

    private val local = MutableStateFlow(Local())
    private val readOnly = !canSendPrompts

    /**
     * Makes the hub call **and** the `local.update` that applies its reply one
     * critical section, across [read]'s four callers — `load()`, `refresh()`,
     * a send's follow-up, and the event-triggered refetch above — none of
     * which otherwise know about each other's coroutines.
     *
     * Both have to be inside the lock, not just the call: locking only
     * `actions.conversation(sessionId)` would still let a second caller
     * acquire the lock, fetch, and even apply its own result the instant the
     * first caller's *fetch* returns — before that first caller has applied
     * its own — which is the same out-of-order-apply race under a different
     * name. With both inside, a caller cannot start its fetch until every
     * earlier caller has fetched *and* applied, so whichever reply is applied
     * last is always the one that was asked for last, onto
     * [Conversation.appending], whose own overlap heuristic is already
     * documented as able to reorder or duplicate turns if fed out of order.
     * Every requested read still runs — this only makes them queue rather
     * than race.
     */
    private val fetchLock = Mutex()

    val state: StateFlow<SessionUiState> = combine(fleet.sessions, local) { rows, l ->
        assemble(rows.firstOrNull { it.id == sessionId }, l)
    }.stateIn(scope, SharingStarted.Eagerly, assemble(row(), local.value))

    init {
        // Started here rather than from `load()`: `state` above is already
        // eager, and a subscription that only exists after the screen's first
        // explicit call would miss an event racing that call.
        scope.launch {
            fleet.sessionChanges
                .filter { it == sessionId }
                .debounce(SESSION_EVENT_DEBOUNCE)
                .collect { read(first = false) }
        }
    }

    /** The first read, when the screen opens. */
    fun load(): Job = fetch(first = true)

    /** A later read, which folds any new turns onto what is already shown. */
    fun refresh(): Job = fetch(first = false)

    fun onDraftChange(text: String) {
        local.update { it.copy(draft = text) }
    }

    /**
     * Clear the banner (review N-B1).
     *
     * Two of five screens had a Dismiss and three did not, and the three
     * without are where an error can sit longest: a send or a read that failed
     * leaves its sentence on screen until the next one succeeds, and on a hub
     * that is down that is never. The conversation behind it is still the last
     * good picture; an error a person has read and cannot put away just teaches
     * them to stop reading the banner, which is the one thing it must not do.
     */
    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /**
     * Send what is typed, then pull the reply in.
     *
     * The box is disabled for the whole call — [SessionUiState.sending] — and a
     * refused prompt keeps the draft, because retyping something the hub
     * bounced is a poor way to find out it was busy.
     */
    fun send(): Job = scope.launch {
        val current = local.value
        // The same rule as [SessionUiState.canSend], read from the sources
        // rather than from `state`: `state` is a `stateIn` of a `combine`, so
        // its value trails the last `onDraftChange` by however long the
        // collector takes to be resumed. A send must not depend on that.
        if (!canSendNow(current)) return@launch
        val text = current.draft
        local.update { it.copy(sending = true, error = null) }
        try {
            actions.sendPrompt(sessionId, text)
            local.update { it.copy(sending = false, draft = "") }
            read(first = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(sending = false, error = explain(t)) }
        }
    }

    private fun canSendNow(l: Local): Boolean =
        !l.sending && !readOnly && l.draft.isNotBlank() && row() != null

    private fun row(): SessionRow? = fleet.sessions.value.firstOrNull { it.id == sessionId }

    private fun fetch(first: Boolean): Job = scope.launch { read(first) }

    private suspend fun read(first: Boolean) {
        local.update { it.copy(loading = first, error = null) }
        try {
            // The fetch AND the apply are one critical section: releasing the
            // lock between them would let a second caller's fetch start (and
            // even finish and apply) before this one's own apply has run,
            // which is the same out-of-order-apply race the lock exists to
            // rule out — see [fetchLock].
            fetchLock.withLock {
                val fresh = actions.conversation(sessionId)
                local.update { it.copy(
                    conversation = local.value.conversation.appending(fresh),
                    loaded = true,
                    loading = false,
                ) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Outside the lock: a failed read writes no conversation, so it has
            // nothing to serialize against another caller's apply, and letting
            // it fall out of the lock immediately is what lets a queued caller
            // proceed without waiting on this one's error bookkeeping too.
            // The conversation on screen stays: a failed read is not evidence
            // that what was already said has stopped being true.
            local.update { it.copy(loading = false, error = explain(t)) }
        }
    }

    private fun assemble(row: SessionRow?, l: Local) = SessionUiState(
        session = row,
        conversation = l.conversation,
        loaded = l.loaded,
        draft = l.draft,
        loading = l.loading,
        sending = l.sending,
        readOnly = readOnly,
        error = l.error,
    )
}

/**
 * How long to let `session:*` frames for the open session settle before
 * refetching its conversation.
 *
 * Long enough that the several row updates one turn can produce — working,
 * then idle, then a final `current_activity` — coalesce into the one read
 * that actually shows the reply; short enough that the screen still feels
 * live rather than polled.
 */
internal val SESSION_EVENT_DEBOUNCE = 500.milliseconds
