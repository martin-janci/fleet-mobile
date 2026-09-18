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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * does not: `session_conversation` is a tool call, and it is fetched when the
 * screen opens and whenever it is refreshed.
 *
 * A refresh **appends**. The hub answers a rolling window of the tail, so a
 * second read overlaps the first rather than continuing it; see
 * [dev.claudefleet.mobile.model.appending].
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

    val state: StateFlow<SessionUiState> = combine(fleet.sessions, local) { rows, l ->
        assemble(rows.firstOrNull { it.id == sessionId }, l)
    }.stateIn(scope, SharingStarted.Eagerly, assemble(row(), local.value))

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
            val fresh = actions.conversation(sessionId)
            local.update { it.copy(
                conversation = local.value.conversation.appending(fresh),
                loaded = true,
                loading = false,
            ) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
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
