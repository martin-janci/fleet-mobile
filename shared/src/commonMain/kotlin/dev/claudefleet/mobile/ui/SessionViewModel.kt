@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ALL_SESSIONS_CHANGED
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.appending
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
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
    /** True while the first-ever read (opening the screen) is outstanding. */
    val loading: Boolean = false,
    /**
     * True while any later read — Refresh, a send's follow-up, or an
     * event-triggered refetch — is outstanding: queued or actually fetching.
     * Distinct from [loading] so the two never clobber each other when one
     * kind lands while the other is still in flight.
     */
    val refreshing: Boolean = false,
    val sending: Boolean = false,
    /** True when this device's credential is `readonly` and may not send. */
    val readOnly: Boolean = false,
    /**
     * True only when the fleet's connection is [ConnectionStatus.Connected].
     * Reconnecting and fully offline both disable sending — a prompt typed
     * while the hub is unreachable has nowhere to go, and the design says
     * actions are disabled rather than hidden while the last snapshot stays on
     * screen.
     */
    val connected: Boolean = true,
    val error: Friendly? = null,
    /** True when the last read said [NO_TRANSCRIPT]: nothing has been said yet, not a failure. */
    val silent: Boolean = false,
) {
    /**
     * Whether the send button does anything. Blank drafts are not prompts, a
     * second prompt while the first is in flight would race the hub's turn
     * counter, a dead session has no REPL to type into, a readonly token is
     * refused `send_prompt` by the hub, and an unreachable hub has nowhere to
     * deliver it. The draft itself is untouched by any of this — only the
     * button goes dark.
     */
    val canSend: Boolean
        get() = !sending && !readOnly && connected && session != null && draft.isNotBlank()
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
 * the reply in — and after a reconnect resync (a `ready` or `lagged` frame),
 * which carries no per-row event of its own for anything that changed during
 * the gap. That subscription is debounced ([SESSION_EVENT_DEBOUNCE]) so a
 * burst of frames during one turn costs one read, and it runs for as long as
 * this view model does: the screen owns [scope], so closing the screen stops
 * it the same way it stops everything else here.
 *
 * A refresh — pull-to-refresh, after a send, or event-triggered — **appends**.
 * The hub answers a rolling window of the tail, so a second read overlaps the
 * first rather than continuing it; see [dev.claudefleet.mobile.model.appending].
 * Those four callers never race each other's hub call, either: [fetchLock]
 * serializes it, so results still apply in the order they were asked for.
 * [requestRead] also coalesces them — see its KDoc — so a burst of taps or
 * events costs at most one extra hub call beyond whichever is already running.
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
     * `local.value = local.value.copy(...)`. The latter is a read-modify-write,
     * and two coroutines in this scope really can interleave across one: a
     * pull-to-refresh while a send's follow-up read is in flight, or a double
     * tap, would lose one merge or clear `loading` while the other call was
     * still running.
     */
    private data class Local(
        val conversation: Conversation = Conversation(),
        val loaded: Boolean = false,
        val draft: String = "",
        val loading: Boolean = false,
        val refreshing: Boolean = false,
        val sending: Boolean = false,
        val error: Friendly? = null,
        val silent: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val readOnly = !canSendPrompts

    /**
     * Makes the hub call **and** the `local.update` that applies its reply one
     * critical section, across [requestRead]'s four callers — `load()`,
     * `refresh()`, a send's follow-up, and the event-triggered refetch above —
     * none of which otherwise know about each other's coroutines.
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

    /**
     * One coalesced request for a fresh read. [first] starts as whatever
     * created this generation and may be upgraded to `true` later — never
     * downgraded — if a `load()` is folded into an already-*queued*
     * non-first generation (never a running one — see [queued]); once
     * anything needs the first-load indicator, this generation needs it
     * until it is done. [done] resolves once this generation's read has
     * applied or failed — both complete it normally, matching the rule that
     * a read's failure is reported through [Local.error] rather than thrown
     * to its callers.
     */
    private class Generation(var first: Boolean) {
        val done = CompletableDeferred<Unit>()
    }

    /**
     * Guards [running] and [queued] alone — a short, non-suspending decision,
     * never the hub call itself (that stays [fetchLock]'s job).
     */
    private val queueGate = Mutex()

    /** The generation currently inside [fetchLock], fetching and applying. */
    private var running: Generation? = null

    /**
     * The next generation, registered but not yet fetching. A request that
     * arrives while this is non-null is folded into it: that read has not
     * started its hub call yet, so it will still answer for whatever
     * prompted the new request too. A request that arrives once nothing is
     * queued — whether or not one is [running] — starts a fresh generation,
     * because a generation already fetching cannot retroactively cover a
     * request made after its fetch began.
     */
    private var queued: Generation? = null

    val state: StateFlow<SessionUiState> = combine(fleet.sessions, fleet.status, local) { rows, status, l ->
        assemble(rows.firstOrNull { it.id == sessionId }, status, l)
    }.stateIn(scope, SharingStarted.Eagerly, assemble(row(), fleet.status.value, local.value))

    init {
        // Started here rather than from `load()`: `state` above is already
        // eager, and a subscription that only exists after the screen's first
        // explicit call would miss an event racing that call.
        scope.launch {
            fleet.sessionChanges
                // `ALL_SESSIONS_CHANGED` is the resync sentinel a `ready` or
                // `lagged` frame emits once its own refetch has landed — the
                // open session's reply may have arrived during the gap, so
                // this screen refetches too, the same as it would for its own id.
                .filter { it == sessionId || it == ALL_SESSIONS_CHANGED }
                .debounce(SESSION_EVENT_DEBOUNCE)
                .collect { requestRead(first = false) }
        }
    }

    /** The first read, when the screen opens. */
    fun load(): Job = scope.launch { requestRead(first = true) }

    /** A later read, which folds any new turns onto what is already shown. */
    fun refresh(): Job = scope.launch { requestRead(first = false) }

    fun onDraftChange(text: String) {
        local.update { it.copy(draft = text) }
    }

    /**
     * Clear the banner. See [SessionsViewModel.dismissError]; here it is a send
     * or a read that failed, rather than a refresh.
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
            requestRead(first = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(sending = false, error = friendly(t)) }
        }
    }

    private fun canSendNow(l: Local): Boolean =
        !l.sending && !readOnly && connected() && l.draft.isNotBlank() && row() != null

    private fun connected(): Boolean = fleet.status.value is ConnectionStatus.Connected

    private fun row(): SessionRow? = fleet.sessions.value.firstOrNull { it.id == sessionId }

    /**
     * Ask for a fresh conversation read, coalescing with whatever is already
     * queued. Every caller — `load()`, `refresh()`, a send's follow-up, and
     * the event-triggered subscription — goes through this rather than
     * calling the hub itself.
     *
     * Coalescing exists because none of the four callers know about each
     * other: without it, a burst of taps or event frames each launches its
     * own coroutine, and every one of them queues its own hub call behind
     * [fetchLock] — a call that can take up to the hub's own call timeout —
     * even though only the *last* one's result will end up on screen. Here, a
     * request folds into [queued] when one exists (its read has not started
     * yet, so it will still answer for this request too); otherwise it starts
     * a new [Generation]. A request is never dropped: whichever generation it
     * joins, [Generation.done] only resolves once that generation's read has
     * actually run.
     */
    private suspend fun requestRead(first: Boolean) {
        var created: Generation? = null
        val target = withQueueGate {
            val existing = queued
            if (existing != null) {
                if (first) existing.first = true
                existing
            } else {
                Generation(first).also {
                    queued = it
                    created = it
                }
            }
        }
        local.update { it.copy(error = null) }
        if (created != null) scope.launch { runGeneration(target) }
        target.done.await()
    }

    /** Runs one [Generation]'s hub call and apply, then completes it. */
    private suspend fun runGeneration(generation: Generation) {
        try {
            // The fetch AND the apply are one critical section: releasing the
            // lock between them would let a second caller's fetch start (and
            // even finish and apply) before this one's own apply has run,
            // which is the same out-of-order-apply race the lock exists to
            // rule out — see [fetchLock].
            fetchLock.withLock {
                // Committed to fetching now: a request arriving after this
                // point must get its own successor generation rather than
                // being folded into a read whose snapshot cannot reflect it.
                withQueueGate {
                    if (queued === generation) queued = null
                    running = generation
                }
                val fresh = actions.conversation(sessionId)
                local.update { it.copy(
                    conversation = it.conversation.appending(fresh),
                    loaded = true,
                    error = null,
                    silent = false,
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
            //
            // `E_NO_TRANSCRIPT` lands here too — the hub answers a read with a
            // tool refusal even when the refusal just means "nothing said
            // yet" — so this is also where that gets told apart from a real
            // failure: [Friendly.isError] decides whether a banner shows at
            // all, and [SessionUiState.silent] is what the empty state reads
            // instead of it.
            val f = friendly(t)
            local.update { it.copy(
                loaded = true,
                silent = t is HubError.Tool && t.code == NO_TRANSCRIPT,
                error = f.takeIf { e -> e.isError },
            ) }
        } finally {
            // `NonCancellable`: this must clear the outstanding flags and
            // release anything waiting on `done` even when the coroutine
            // running this generation was itself cancelled (the screen
            // closing cancels `scope`, which cancels this along with every
            // other in-flight read) — otherwise a cancelled generation would
            // leave `loading`/`refreshing` stuck and a coalesced caller's
            // `done.await()` would hang forever.
            withContext(NonCancellable) {
                withQueueGate { if (running === generation) running = null }
                generation.done.complete(Unit)
            }
        }
    }

    /** Mutates [running]/[queued] under [queueGate] and republishes the flags they derive. */
    private suspend fun <T> withQueueGate(block: () -> T): T = queueGate.withLock {
        val result = block()
        local.update {
            it.copy(
                loading = running?.first == true || queued?.first == true,
                refreshing = running?.first == false || queued?.first == false,
            )
        }
        result
    }

    private fun assemble(row: SessionRow?, status: ConnectionStatus, l: Local) = SessionUiState(
        session = row,
        conversation = l.conversation,
        loaded = l.loaded,
        draft = l.draft,
        loading = l.loading,
        refreshing = l.refreshing,
        sending = l.sending,
        readOnly = readOnly,
        connected = status is ConnectionStatus.Connected,
        error = l.error,
        silent = l.silent,
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
