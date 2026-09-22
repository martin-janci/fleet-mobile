@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ALL_SESSIONS_CHANGED
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.data.STOPPED
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.appending
import dev.claudefleet.mobile.model.tailMarker
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
     * True when the fleet's connection is [ConnectionStatus.Connected], OR
     * this screen's own probe of the hub (`fleet_health`, via
     * [SessionActions.ping]) last answered true and the hub is not
     * [ConnectionStatus.Refused].
     *
     * A dropped `/events` stream is not the same fact as an unreachable hub —
     * the stream can flap for reasons that have nothing to do with the hub
     * (a backgrounded phone's radio, a flaky Wi-Fi hop) — so Send follows the
     * hub, not the stream. Fully offline (never paired, or a revoked
     * credential) still disables sending: the design says actions are
     * disabled rather than hidden while the last snapshot stays on screen.
     * A refused hub is the one case where a reachable hub still disables it:
     * it answers, and this build has decided it must not be spoken to.
     */
    val connected: Boolean = true,
    /**
     * What this screen's last probe of the hub said, or null when it has not
     * probed — connected streams and refused hubs never do.
     *
     * Drawn rather than merely used: a stream that is down while the hub
     * itself answers is a third connection state, and without this the banner
     * could only say "reconnecting" over a screen whose Send button was
     * live, which reads as a contradiction. See `connectionNotice`.
     */
    val hubReachable: Boolean? = null,
    val error: Friendly? = null,
    /** True when the last read said [NO_TRANSCRIPT]: nothing has been said yet, not a failure. */
    val silent: Boolean = false,
    /**
     * True when a read grew the tail — a new turn, or the live turn growing
     * new items — while the reader was scrolled away from the bottom (see
     * [SessionViewModel.onAtBottom]). The jump pill reads this to choose
     * between "↓ Latest" and "↓ New reply"; it clears once the reader is
     * told to be back at the bottom.
     */
    val newReply: Boolean = false,
    /**
     * What to draw for a session the hub says is blocked or stuck, or null
     * when there is nothing to answer. Derived from the row and the hub's
     * version by [blockedCard] — the one place that mapping lives — so it
     * appears and disappears with the row rather than being cleared by hand
     * after an answer goes out.
     */
    val card: BlockedCard? = null,
    /** True while an answer is in flight: the chips go dark and a spinner shows. */
    val answering: Boolean = false,
    /**
     * True when the last answer's [SessionActions.waitForTurn] timed out
     * rather than seeing the turn move. The answer was delivered; the agent
     * has simply not moved on yet, which is a different thing from a failure
     * and gets a line on the card rather than an error banner.
     */
    val stillWaiting: Boolean = false,
    /**
     * The captured tmux pane, while the terminal fallback is expanded — the
     * way through for anything the card cannot offer a chip for. Null when it
     * is hidden.
     */
    val terminal: String? = null,
    /**
     * True while a management call — [SessionViewModel.restart],
     * [SessionViewModel.safeKill], [SessionViewModel.kill],
     * [SessionViewModel.setTags] or [SessionViewModel.rename] — and the
     * refetch that follows it are outstanding. The overflow menu's items go
     * dark for the whole span, the same way [sending]/[answering] darken the
     * composer and the card, so a second tap cannot start a second call
     * before the first one's own refetch has even landed.
     */
    val busy: Boolean = false,
    /**
     * Unix seconds, refreshed every 30s by a ticker — what
     * [dev.claudefleet.mobile.ui.components.StatusStrip] computes the bar's
     * elapsed/idle-since wording against. The same pattern as
     * [SessionsUiState.nowSeconds], one screen down: a `StateFlow` a device's
     * clock actually moves, rather than a value fixed at whenever this state
     * happened to be built.
     */
    val nowSeconds: Long = 0,
) {
    /**
     * Whether the ⋮ menu is offered at all: a readonly credential may not
     * call any of these tools, a session that has left the fleet has nothing
     * to manage, and the hub refuses every one of these calls against the
     * controller (`E_INVALID_STATE`) — so the menu simply is not drawn there
     * rather than drawn and refused. [canRestart] and [canKill] are further
     * narrowings of this, never a looser rule: a screen this is false on
     * offers no management action at all.
     */
    val canManage: Boolean
        get() = !readOnly && session != null && !session.isController

    /** Restart carries no narrowing beyond [canManage] — see [BlockedCard.offerRestart] for when it is worth showing. */
    val canRestart: Boolean
        get() = canManage

    /**
     * [canManage], narrowed once more: the hub refuses `kill_session`
     * against an `external` session with `E_INVALID_STATE`, so *Kill now* is
     * not offered there even though Restart and Tags still are.
     */
    val canKill: Boolean
        get() = canManage && session?.kind != "external"

    /**
     * The hub's own progress through a `safe_kill_session` retirement — the
     * row's `safe_kill_state` — or null while none is armed. Drawn as a
     * `SuggestionChip` in the status strip for as long as it is non-null.
     */
    val safeKillState: String?
        get() = session?.safeKillState

    /**
     * Whether the send button does anything. Blank drafts are not prompts, a
     * second prompt while the first is in flight would race the hub's turn
     * counter, a dead session has no REPL to type into, a readonly token is
     * refused `send_prompt` by the hub, and an unreachable hub has nowhere to
     * deliver it. The draft itself is untouched by any of this — only the
     * button goes dark.
     *
     * [answering] is in here for the same reason [sending] is, across a path
     * rather than within one: an answer is out for as long as its
     * [ANSWER_WAIT_SECONDS] wait, and a prompt typed into the composer
     * meanwhile would race the very turn counter that answer is waiting past.
     * Two single-flight guards that did not know about each other were no
     * guard at all — see [SessionViewModel.answer]. [busy] joined them for
     * the same reason: a management call (`restart`, `kill`, …) is a hub
     * write too, and one in flight must darken Send exactly as a prompt or an
     * answer already in flight does — see [idle].
     */
    val canSend: Boolean
        get() = idle(sending, answering, busy) && !readOnly && connected && session != null && draft.isNotBlank()

    /**
     * Whether the card's answer chips do anything — the other half of
     * [canSend], and the same facts in the same order. Drawn by
     * `BlockedCardView` rather than re-derived there, so a chip is never
     * live for a tap [SessionViewModel.answer] would drop on the floor.
     *
     * A readonly device is the one case the screen handles differently: it
     * hides the chips outright instead of dimming them, because the hub would
     * refuse the call whatever the connection does.
     */
    val canAnswer: Boolean
        get() = idle(sending, answering, busy) && !readOnly && connected && card != null
}

/**
 * "Nothing else on this screen already holds a hub write outstanding" — the
 * one rule shared, term for term, by [SessionUiState.canSend], `canSendNow`,
 * [SessionUiState.canAnswer], `canAnswerNow`, and the management family's own
 * `runManaged` guard in [SessionViewModel]. It used to be three separate
 * copies: [SessionUiState.canSend]/[SessionUiState.canAnswer] and their
 * `…Now` twins agreed with each other, but `runManaged` — added for
 * `restart`/`safeKill`/`kill`/`setTags`/`rename` — checked only its own
 * `busy` flag, and neither `canSendNow` nor `canAnswerNow` was taught about
 * `busy` in return. A kill could run while an answer was still out; Send
 * could fire while a restart's own refetch was still in flight. Every write
 * this screen can start now reads the same three flags, in the same order,
 * from this one place.
 */
internal fun idle(sending: Boolean, answering: Boolean, busy: Boolean): Boolean = !sending && !answering && !busy

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
    private val clock: () -> Long = { epochSeconds() },
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
        /**
         * What the screen last reported through [onAtBottom]. Starts true:
         * a screen that has not scrolled at all — including one that has
         * not rendered its first frame yet — is at the newest turn, not
         * away from it, so an early refetch has nothing to flag.
         */
        val atBottom: Boolean = true,
        val newReply: Boolean = false,
        val answering: Boolean = false,
        val stillWaiting: Boolean = false,
        /**
         * Whether the reader asked for the terminal. Kept apart from
         * [terminal] so that a capture which has not answered yet — or one
         * that failed — still counts as "shown", which is what makes the
         * session-event path keep refreshing it rather than going quiet after
         * one bad call.
         */
        val terminalShown: Boolean = false,
        val terminal: String? = null,
        /** See [SessionUiState.busy]. */
        val busy: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val readOnly = !canSendPrompts

    /**
     * The last answer from probing the hub directly while [fleet]'s stream is
     * not [ConnectionStatus.Connected] — null before the first probe of the
     * current [FleetState.status], and reset to null every time that status
     * changes, because an answer about one connection state says nothing
     * about the next. [SessionUiState.connected] and [canSendNow] both read
     * it alongside [FleetState.status], never in place of it, through the one
     * [isConnected] rule.
     */
    private val probe = MutableStateFlow<Boolean?>(null)

    /**
     * Unix seconds, ticked every 30s — see [SessionUiState.nowSeconds]. The
     * same shape as [SessionsViewModel]'s own `now`: a `StateFlow` folded into
     * [state] rather than read fresh by a composable, so the strip's wording
     * advances on the same recomposition every other live fact does.
     */
    private val now = MutableStateFlow(clock())

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

    val state: StateFlow<SessionUiState> =
        // `combine` has no six-flow overload; `probe` and `now` are folded
        // into one `Pair` first rather than nesting a second `.stateIn` or
        // hand-rolling a sixth `combine`, so there is still exactly one
        // downstream collector to reason about.
        combine(fleet.sessions, fleet.status, fleet.hubVersion, local, combine(probe, now, ::Pair)) { rows, status, version, l, (probed, nowSeconds) ->
            assemble(rows.firstOrNull { it.id == sessionId }, status, version, l, probed, nowSeconds)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            assemble(row(), fleet.status.value, fleet.hubVersion.value, local.value, probe.value, now.value),
        )

    init {
        // Ticks `now` every 30s — the same period [SessionsViewModel] uses for
        // the fleet list — so the strip's "2 min" / "idle since 2 h" wording
        // advances without a per-second recomposition on a screen a person
        // may leave open for hours.
        scope.launch {
            while (isActive) {
                delay(30_000)
                now.value = clock()
            }
        }
        // Started here rather than from `load()`: `state` above is already
        // eager, and a subscription that only exists after the screen's first
        // explicit call would miss an event racing that call.
        //
        // Whenever the stream is not Connected AND the hub is worth asking
        // ([worthProbing]), probe it directly — one call in flight,
        // [PROBE_DEBOUNCE] apart — until it answers true, and then STOP. A
        // `true` is not a fact that decays: it made Send usable, and asking
        // again every two seconds for the rest of the screen's life was a
        // `fleet_health` call per tick on a phone's radio, forever, for an
        // answer already in hand. The loop re-arms — `probe` back to null,
        // probing resumed — only when `fleet.status` changes, since that is
        // the only thing that can make the last answer stale.
        //
        // It also stops outliving what it answers for. `readOnly` returns
        // before the first call: `canSend` is false for such a screen whatever
        // the hub says, so every probe it made was a call made for nothing —
        // and the app's rule is that it only ever calls tools it has a use
        // for. A repository the lifecycle STOPPED is skipped for the same
        // reason: nothing is reconnecting, so "is the hub up" has no one
        // waiting on the answer.
        //
        // One coroutine, not a collector that launches a second one per
        // disconnect: `fleet.status` is a `StateFlow`, so reading `.value`
        // directly here (rather than `collect`ing it) needs no extra hop
        // before the very first probe of an already-disconnected screen can
        // land.
        //
        // `CoroutineStart.UNDISPATCHED`: a screen constructed while already
        // disconnected must have that first probe *in flight the instant the
        // constructor returns*, not merely scheduled — otherwise `load()`'s
        // own coroutine, racing this one for the shared test/UI dispatcher,
        // could apply its "loaded" state before this one has ever run, and
        // `canSend` would read a probe result that has not happened yet.
        // Ordinary (dispatched) `launch` only queues the body; UNDISPATCHED
        // runs it synchronously up to its first real suspension point (here,
        // [actions.ping]'s own suspension, or — on a fake with none — the
        // `delay` below), which is exactly "started", not "about to start".
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (readOnly) return@launch
            var answeredFor: ConnectionStatus? = null
            while (true) {
                val status = fleet.status.value
                if (status != answeredFor) {
                    // A different connection state: whatever the last probe
                    // said was about the old one.
                    probe.value = null
                    answeredFor = status
                }
                if (!worthProbing(status) || probe.value == true) {
                    // Nothing more to ask until the picture changes. `first`
                    // on a `StateFlow` sees the current value before it
                    // suspends, so a status that moved while this line was
                    // being reached returns at once rather than being missed.
                    fleet.status.first { it != status }
                } else {
                    probe.value = actions.ping()
                    delay(PROBE_DEBOUNCE)
                }
            }
        }
        // `stillWaiting` belongs to the card it was reported for: it says
        // "the answer you just sent was delivered and the agent has not moved
        // yet". Once that card is gone the episode is over, and leaving the
        // flag set meant the NEXT prompt — a different question, nothing sent
        // for it — drew the line too. Collecting the already-derived
        // `state.card` rather than re-deciding here what "blocked" means
        // keeps that rule in `blockedCard` alone; the `if` makes the update a
        // no-op (same instance, so no re-emission) whenever there is nothing
        // to clear, which is what stops this from feeding itself.
        scope.launch {
            state.collect { s ->
                if (s.card == null) local.update { if (it.stillWaiting) it.copy(stillWaiting = false) else it }
            }
        }
        scope.launch {
            fleet.sessionChanges
                // `ALL_SESSIONS_CHANGED` is the resync sentinel a `ready` or
                // `lagged` frame emits once its own refetch has landed — the
                // open session's reply may have arrived during the gap, so
                // this screen refetches too, the same as it would for its own id.
                .filter { it == sessionId || it == ALL_SESSIONS_CHANGED }
                .debounce(SESSION_EVENT_DEBOUNCE)
                .collect {
                    // The capture first: it is the thing a person staring at a
                    // blocked pane is watching, and `requestRead` suspends
                    // until its generation has fetched AND applied.
                    if (local.value.terminalShown) captureTerminal()
                    requestRead(first = false)
                }
        }
    }

    /**
     * The first read, when the screen opens. A no-op against a refused hub —
     * see [requestRead].
     */
    fun load(): Job = scope.launch { requestRead(first = true) }

    /**
     * A later read, which folds any new turns onto what is already shown.
     * A no-op against a refused hub — see [requestRead].
     */
    fun refresh(): Job = scope.launch { requestRead(first = false) }

    fun onDraftChange(text: String) {
        local.update { it.copy(draft = text) }
    }

    /**
     * The screen's own report of whether the reader is at the newest turn —
     * the truth [Local.atBottom] tracks for [newReply], and, on `true`, the
     * signal that any pending new-reply flag is resolved: the reader just
     * got there, whether by the jump pill or by scrolling there themselves.
     */
    fun onAtBottom(atBottom: Boolean) {
        local.update { it.copy(atBottom = atBottom, newReply = if (atBottom) false else it.newReply) }
    }

    /**
     * Clear the banner. See [SessionsViewModel.dismissError]; here it is a send
     * or a read that failed, rather than a refresh.
     */
    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /**
     * Answer the blocked agent with one of the card's own [Answer]s, then wait
     * for its turn counter to move past the one the send reported.
     *
     * Nothing clears the card here. It is derived from the row (see
     * [SessionUiState.card]), so the thing that makes it go away is the hub
     * publishing a row that is no longer blocked — which is exactly the fact
     * [SessionActions.waitForTurn] is waiting for. A wait that times out
     * instead leaves the card up and says [SessionUiState.stillWaiting]: the
     * answer was delivered, the agent has not moved yet.
     *
     * Gated exactly as [send] is on the credential: neither `send_prompt` nor
     * its `keys` form is in the hub's readonly allow-list, so a readonly
     * device makes no call at all — and the screen hides the chips rather than
     * offering a tap that would be refused.
     *
     * One answer at a time, and never alongside a prompt from the composer: a
     * second delivery while the first is in flight would go against a turn
     * counter the first is still waiting on, and the REPL would see two
     * answers to one prompt. [SessionUiState.canSend] carries the other half
     * of that rule.
     *
     * Gated on the hub being reachable exactly as [send] is, through the same
     * [isConnected] rule — which also covers a [ConnectionStatus.Refused] hub,
     * the one this screen must not call even though it answers. The card
     * cannot be relied on to disappear there: a refusal picked up on
     * reconnect leaves the previous connection's rows in place, so the card
     * outlives it and the gate has to be here.
     */
    fun answer(a: Answer): Job = scope.launch {
        if (!canAnswerNow(local.value)) return@launch
        local.update { it.copy(answering = true, stillWaiting = false, error = null) }
        try {
            val receipt = when (a) {
                // A numbered option is typed as its number, which is what the
                // REPL's own prompt asks for; the trust prompt's y/n is text
                // for the same reason. Only the bare keystrokes go through
                // `send_prompt { keys }` — an empty prompt is not a key.
                is Answer.Option -> actions.sendPrompt(sessionId, a.n.toString())
                is Answer.Text -> actions.sendPrompt(sessionId, a.text)
                Answer.Enter -> actions.sendKeys(sessionId, "Enter")
                Answer.Escape -> actions.sendKeys(sessionId, "Escape")
                Answer.Interrupt -> actions.sendKeys(sessionId, "C-c")
            }
            val wait = actions.waitForTurn(sessionId, receipt.turnSeqBefore, timeoutS = ANSWER_WAIT_SECONDS)
            local.update { it.copy(answering = false, stillWaiting = wait.status != WAIT_SATISFIED) }
            requestRead(first = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(answering = false, error = friendly(t)) }
        }
    }

    /**
     * Expand the terminal fallback and capture the pane once. While it is
     * shown, the same debounced session-event path that refetches the
     * conversation re-captures it, so a pane that moves on its own follows
     * without polling.
     */
    fun showTerminal(): Job = scope.launch {
        local.update { it.copy(terminalShown = true) }
        captureTerminal()
    }

    /** Collapse it, and drop the capture with it rather than keeping a stale pane around. */
    fun hideTerminal() {
        local.update { it.copy(terminalShown = false, terminal = null) }
    }

    /**
     * One `capture_session` call and the update that applies it. A failure
     * goes to the banner like every other call this class makes, rather than
     * showing an empty pane that would read as "the terminal is blank".
     */
    private suspend fun captureTerminal() {
        // The same rule as [requestRead]: a refused hub gets no tool call from
        // this screen, through any path.
        if (refused()) return
        try {
            val text = actions.capture(sessionId)
            // Only if it is still wanted: a `hideTerminal()` while this call
            // was in flight must not be undone by its reply landing.
            local.update { if (it.terminalShown) it.copy(terminal = text) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        }
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
        deliver(current.draft, clearDraft = true)
    }

    /**
     * Send [text] through the same guarded, single-flight path as [send] —
     * for the status strip's `/compact` chip and anything else that has to
     * speak to the REPL without going through what is sitting in the
     * composer's draft. Reuses [deliver] rather than duplicating [send]'s
     * body, so there is exactly one rule for "how this screen writes to the
     * hub", not two that could drift apart.
     *
     * Guarded exactly like [send] — readonly, connected, [idle] — minus the
     * blank-draft check, which has nothing to do with a caller-supplied
     * command that is never blank to begin with.
     */
    fun sendCommand(text: String): Job = scope.launch {
        val current = local.value
        if (!canWriteNow(current) || row() == null) return@launch
        deliver(text, clearDraft = false)
    }

    /** The guarded hub write both [send] and [sendCommand] make, and the refetch that follows it. */
    private suspend fun deliver(text: String, clearDraft: Boolean) {
        local.update { it.copy(sending = true, error = null) }
        try {
            actions.sendPrompt(sessionId, text)
            local.update { it.copy(sending = false, draft = if (clearDraft) "" else it.draft) }
            requestRead(first = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(sending = false, error = friendly(t)) }
        }
    }

    /**
     * Kill and recreate the tmux session in place — for a wedged REPL. Also
     * what the blocked card's own Restart button calls (see
     * [SessionUiState.canRestart]) when it draws one at all.
     */
    fun restart(): Job = runManaged(::canRestartNow) { actions.restart(sessionId) }

    /** Ask the session to persist its work, then arm deletion once it is clean. */
    fun safeKill(): Job = runManaged(::canManageNow) { actions.safeKill(sessionId) }

    /**
     * Kill the session now, without waiting for it to persist anything. The
     * hub refuses this against the controller or an `external` session with
     * `E_INVALID_STATE` — [SessionUiState.canKill] is what keeps the menu
     * from offering a tap that would only come back refused.
     */
    fun kill(): Job = runManaged(::canKillNow) { actions.kill(sessionId) }

    /** Replace the session's tags with [tags]. */
    fun setTags(tags: List<String>): Job = runManaged(::canManageNow) { actions.setTags(sessionId, tags) }

    /** Set the session's friendly display name to [name]. */
    fun rename(name: String): Job = runManaged(::canManageNow) { actions.rename(sessionId, name) }

    /**
     * One management call — [restart], [safeKill], [kill], [setTags] or
     * [rename] — gated and bracketed the same way for all five: [guard] is
     * this action's own live-source rule ([canRestartNow] for restart,
     * [canKillNow] for kill, [canManageNow] for the rest — read from the live
     * sources rather than from `state`, exactly as [canSendNow]/[canAnswerNow]
     * are, since `state` trails its inputs by a dispatch), [connected] is the
     * same hub-reachability gate [send]/[answer] use, and [idle] is the same
     * "nothing else in flight" rule [canSendNow]/[canAnswerNow] use — a
     * management call must not race a prompt or an answer any more than they
     * may race each other, and [Local.busy] is its own third term in that
     * same rule, so a second management call cannot start while the first's
     * own refetch is still outstanding either.
     *
     * [SessionUiState.busy] stays true across the follow-up [requestRead]
     * too, not just the call itself — a `finally` covers both the success and
     * the caught-failure path — so the menu cannot be tapped again before the
     * row it would act on next has actually been refreshed, and Send/answer
     * cannot fire into the middle of it either.
     */
    private fun runManaged(guard: () -> Boolean, call: suspend () -> Unit): Job = scope.launch {
        val l = local.value
        if (!guard() || !connected() || !idle(l.sending, l.answering, l.busy)) return@launch
        local.update { it.copy(busy = true, error = null) }
        try {
            call()
            requestRead(first = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        } finally {
            local.update { it.copy(busy = false) }
        }
    }

    /** [SessionUiState.canManage] read from the live sources — see [runManaged]. */
    private fun canManageNow(): Boolean = !readOnly && row()?.isController == false

    /** [SessionUiState.canRestart] read from the live sources — see [runManaged]. */
    private fun canRestartNow(): Boolean = canManageNow()

    /** [SessionUiState.canKill] read from the live sources — see [runManaged]. */
    private fun canKillNow(): Boolean = canManageNow() && row()?.kind != "external"

    /**
     * [SessionUiState.canAnswer] read from the live sources rather than from
     * `state` — which is a `stateIn` of a `combine` and therefore trails its
     * inputs by a dispatch. A tap must not depend on that. The card's own
     * presence is left out here: `answer` is only reachable from a card, and
     * re-deciding what "blocked" means at the moment of the tap would be a
     * second copy of `blockedCard`'s rule.
     */
    private fun canAnswerNow(l: Local): Boolean =
        !readOnly && idle(l.sending, l.answering, l.busy) && connected()

    /** The part of [canSendNow] that does not care what is being sent — shared with [sendCommand]. */
    private fun canWriteNow(l: Local): Boolean = idle(l.sending, l.answering, l.busy) && !readOnly && connected()

    private fun canSendNow(l: Local): Boolean =
        canWriteNow(l) && l.draft.isNotBlank() && row() != null

    /** [isConnected] read from the live sources, for [send]'s own check. */
    private fun connected(): Boolean = isConnected(fleet.status.value, probe.value)

    private fun row(): SessionRow? = fleet.sessions.value.firstOrNull { it.id == sessionId }

    /** True while the hub named a contract this build refuses to talk across. */
    private fun refused(): Boolean = fleet.status.value is ConnectionStatus.Refused

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
        // A refused hub gets no tool call from this screen, from any of the
        // four callers. The refusal is a statement about the shape of every
        // reply this hub would send, so `session_conversation` is exactly as
        // unreadable as the rows the repository is already dropping — and
        // `loaded` deliberately stays false, so the screen keeps saying
        // nothing rather than claiming an empty conversation it never read.
        // Guarded here rather than in `load()`/`refresh()` so that the
        // event-driven refetch and the send follow-up cannot route around it.
        if (refused()) return
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
                local.update { current ->
                    val appended = current.conversation.appending(fresh)
                    // The same "did the tail move" signal `SessionScreen`'s
                    // own auto-scroll effect keys on: turn count alone misses
                    // the ordinary case of the live turn growing new items
                    // while the agent keeps working, without a new turn ever
                    // starting. One rule, `Conversation.tailMarker()`, rather
                    // than the pair being spelled out by hand in both places.
                    val tailGrew = appended.tailMarker() != current.conversation.tailMarker()
                    current.copy(
                        conversation = appended,
                        loaded = true,
                        error = null,
                        silent = false,
                        newReply = current.newReply || (tailGrew && !current.atBottom),
                    )
                }
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

    private fun assemble(
        row: SessionRow?,
        status: ConnectionStatus,
        hubVersion: String?,
        l: Local,
        probed: Boolean?,
        nowSeconds: Long,
    ) = SessionUiState(
        session = row,
        conversation = l.conversation,
        loaded = l.loaded,
        draft = l.draft,
        loading = l.loading,
        refreshing = l.refreshing,
        sending = l.sending,
        readOnly = readOnly,
        connected = isConnected(status, probed),
        hubReachable = probed,
        error = l.error,
        silent = l.silent,
        newReply = l.newReply,
        // Derived, never stored: `blockedCard` in `Blocked.kt` is the one
        // place that decides what a blocked or stuck row offers, and it is
        // asked here with the hub's own version because the structured-key
        // chips depend on it.
        card = row?.let { blockedCard(it, hubVersion) },
        answering = l.answering,
        stillWaiting = l.stillWaiting,
        terminal = l.terminal,
        busy = l.busy,
        nowSeconds = nowSeconds,
    )
}

/**
 * The one rule for "can this screen reach the hub", used by
 * [SessionUiState.connected] (through `assemble`) and by `send`'s own check
 * against the live sources. It was written twice, in two places that had to
 * agree and nothing made them.
 *
 * A live stream is proof on its own. Otherwise the screen's own probe of the
 * hub stands in for it — except against a [ConnectionStatus.Refused] hub,
 * which answers a probe perfectly well and still must not be sent to.
 */
internal fun isConnected(status: ConnectionStatus, probed: Boolean?): Boolean =
    status is ConnectionStatus.Connected ||
        (probed == true && status !is ConnectionStatus.Refused)

/**
 * Whether asking the hub directly could still change anything.
 *
 * No for a stream that is already [ConnectionStatus.Connected] (the answer is
 * in hand), no for a [ConnectionStatus.Refused] hub (a `true` would only
 * re-enable a button this build has decided must stay dark), and no for the
 * [ConnectionStatus.Offline] the lifecycle itself published through
 * `FleetRepository.stop()` — the app put the stream down, nothing is trying
 * to come back, and a probe has no one waiting on its answer.
 */
internal fun worthProbing(status: ConnectionStatus): Boolean = when (status) {
    is ConnectionStatus.Connected -> false
    is ConnectionStatus.Refused -> false
    is ConnectionStatus.Offline -> status.reason != STOPPED
    is ConnectionStatus.Reconnecting -> true
}

/**
 * How long to wait between probes of the hub while `/events` is down. Long
 * enough that a flapping stream does not turn into a `fleet_health` call on
 * every `Reconnecting(attempt = …)` tick; short enough that a hub which comes
 * back is noticed within a couple of seconds rather than left showing Send
 * disabled long after it would have worked. It bounds a *run* of probes, not
 * the screen's lifetime: the loop stops on the first `true` — see `init`.
 */
internal val PROBE_DEBOUNCE = 2.seconds

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

/**
 * How long an answer waits for the session's turn counter to move before the
 * card says [SessionUiState.stillWaiting] instead.
 *
 * The hub's `wait_for_session` default. Long enough that an agent which
 * simply takes a moment to pick the answer up is not reported as stuck;
 * short enough that a phone screen does not sit on a spinner indefinitely
 * when the REPL never moves at all.
 */
internal const val ANSWER_WAIT_SECONDS: Int = 30

/** What `wait_for_session` answers when the turn actually moved. */
internal const val WAIT_SATISFIED: String = "satisfied"
