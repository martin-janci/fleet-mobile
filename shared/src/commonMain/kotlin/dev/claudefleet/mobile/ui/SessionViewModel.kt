@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.BackgroundOptions
import dev.claudefleet.mobile.data.NewSessionActions
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.claudefleet.mobile.data.ALL_SESSIONS_CHANGED
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.QuickReplyActions
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.data.STOPPED
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.loginLabel
import dev.claudefleet.mobile.model.forAccount
import dev.claudefleet.mobile.model.QueuedPrompt
import dev.claudefleet.mobile.model.HostLogin
import dev.claudefleet.mobile.model.ActivityProbe
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.GrantLevel
import dev.claudefleet.mobile.model.MyAccess
import dev.claudefleet.mobile.model.ConversationSummary
import dev.claudefleet.mobile.model.RepairReport
import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.QuickReply
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.appending
import dev.claudefleet.mobile.model.fingerprint
import dev.claudefleet.mobile.model.tailMarker
import dev.claudefleet.mobile.model.turnsAddedAfter
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.store.Prefs
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
    /**
     * True when this screen may not send a prompt: the device's credential is
     * `readonly`, or the session is shared with this person below drive.
     */
    val readOnly: Boolean = false,
    /**
     * The level the session is shared with this person at (`watch`,
     * `answer`, `drive`), or null when it is theirs, unclaimed, or the hub
     * has not said (redesign 11.10). Not null is someone else's session.
     */
    val share: String? = null,
    /**
     * A dialog's keys (an option's digit, Enter, Escape) may be pressed: the
     * credential may write and the session is not shared at watch. Wider
     * than ![readOnly] by exactly an Answer share.
     */
    val mayPressKeys: Boolean = true,
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
    /**
     * The update stream is up: what the header and the tool rows show is
     * current. False while the stream is down, even when [connected] (the hub
     * answers a probe): nothing on screen moves until it is back.
     */
    val streaming: Boolean = true,
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
     * How many turns arrived at the newest end while the reader was scrolled
     * away from it — the count on the jump pill's badge. Only turns appended
     * after the one that was newest when the reader left the bottom count
     * ([dev.claudefleet.mobile.model.turnsAddedAfter]); the live turn growing
     * is [newReply]'s business, not a new turn. Cleared with [newReply].
     */
    val unseen: Int = 0,
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
    /**
     * The hub has `rewind_conversation` (`HubCapabilities.rewind`). With
     * [canManage], what offers Rewind here, Retry and Fork here under a reply.
     */
    val rewindAvailable: Boolean = false,
    /** The hub has `new_bg_session` and this screen was given a way to call it ([SessionViewModel.startBackground]). */
    val backgroundAvailable: Boolean = false,
    /** It takes read-only and the stop-after limits too (contract 14). */
    val backgroundOptions: Boolean = false,
    /** The session's Claude conversations, newest first; empty until read or on an older hub. */
    val conversations: List<ConversationSummary> = emptyList(),
    /**
     * The earlier conversation on screen instead of the current one, or null.
     * Read-only history: a prompt still goes to the session's current
     * conversation, and a reply there offers no rewind or fork.
     */
    val viewing: ConversationSummary? = null,
    /** The current conversation's older turns may be asked for (see [SessionViewModel.loadOlder]). */
    val canLoadOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    /** The hub can recreate a session / dismiss a ghost (`recreate_session`, `dismiss_ghost_session`). */
    val recreateAvailable: Boolean = false,
    val dismissGhostAvailable: Boolean = false,
    val reviewAvailable: Boolean = false,
    val repairAvailable: Boolean = false,
    /** The keys the full-screen agent's bar may press in the pane ([HubCapabilities.paneKeys]). */
    val paneKeys: Set<String> = HubCapabilities.BASE_PANE_KEYS,
    /** What the last repair found and did, until dismissed. */
    val repair: RepairReport? = null,
    /**
     * The prompt on its way to the hub: out of the box at once and shown at
     * the foot of the conversation as *Sending…*, so a send never looks like
     * it vanished. Null once it landed — or failed, when it is back in the box.
     */
    val pending: String? = null,
    /** [error] came from a send — drawn by the composer, where the thumb is, not under the header. */
    val errorFromSend: Boolean = false,
    /**
     * A prompt the hub did not take, kept in the conversation with Retry and
     * Edit (redesign 14.5, New bar only); the box is left free meanwhile.
     */
    val notSent: NotSent? = null,
    /** The hub keeps a prompt for the session's next idle moment (`queue_prompt`, redesign 14.14). */
    val sendLaterAvailable: Boolean = false,
    /** The prompts the hub keeps for this session, oldest first, as last read. */
    val queued: List<QueuedPrompt> = emptyList(),
    /** What the last Send later did, in words ("Sent now: the session was idle."). */
    val sendLaterNotice: String? = null,
    /** The hub can say which login has room and restart under it (Switch account, step 4.10). */
    val switchAccountAvailable: Boolean = false,
    /** The login Switch account proposes, waiting for the second tap; null when none is proposed. */
    val switchTarget: HostLogin? = null,
    /** A Wait chosen for this limit: unix seconds of the reset it waits for. */
    val waitingUntil: Long? = null,
    /** What the last Switch account said when it had nowhere to go or went. */
    val limitNotice: String? = null,
) {
    /** Send later is offered: the hub keeps prompts and this person may drive the session. */
    val canSendLater: Boolean
        get() = sendLaterAvailable && !readOnly && session != null && connected

    /**
     * The row is paused at its account's limit (the hub's `account_limit`),
     * and this person may restart it under another login: Switch account.
     */
    val canSwitchAccount: Boolean
        get() = switchAccountAvailable && canRestart && session?.attention?.reason == "account_limit"

    /** A working agent can be stopped (Escape) — Send's place while there is nothing to send. */
    val canStop: Boolean
        get() = idle(sending, answering, busy) && !readOnly && connected && card == null &&
            session?.claudeStatus == "working" && draft.isEmpty()

    val canReview: Boolean
        get() = canManage && reviewAvailable

    val canRepair: Boolean
        get() = canManage && repairAvailable

    /**
     * Lost from tmux — a reboot, a killed server — with its row kept: a
     * ghost, which can be brought back (Recreate) or let go (Dismiss).
     */
    val ghost: Boolean
        get() = session?.lostAt != null || session?.status == "ghost"

    val canRecreate: Boolean
        get() = canManage && recreateAvailable

    val canDismissGhost: Boolean
        get() = canManage && ghost && dismissGhostAvailable
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
        get() = !readOnly && share == null && session != null && !session.isController

    /** Whether a reply offers Rewind here, Retry and Fork here at all — see [replyActionsFor]. */
    val canRewind: Boolean
        get() = canManage && rewindAvailable && viewing == null

    /** ⋮ Background agent…: an owner of this session, on a hub that starts one. */
    val canStartBackground: Boolean
        get() = canManage && backgroundAvailable

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
        get() = idle(sending, answering, busy) && !readOnly && connected && session != null && draft.isNotBlank() && card == null

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
        get() = idle(sending, answering, busy) && mayPressKeys && connected && card != null

    /**
     * [answer] may go out from this screen: a key while [mayPressKeys], and
     * anything typed (or C-c) only where a prompt could go too.
     */
    fun mayAnswerWith(answer: Answer): Boolean = if (answer.isKey) mayPressKeys else !readOnly

    /**
     * Whether the question up now can be answered in the person's own words
     * (redesign 14.4): it has a no-and-say-why answer ([declineOption]) and an
     * answer could go out at all. The composer opens for it while the card is up.
     */
    val canAnswerInWords: Boolean
        get() = canAnswer && !readOnly && card?.let(::declineOption) != null

    /** Whether Send would answer in the person's own words right now: [canAnswerInWords] and words to send. */
    val canSendWords: Boolean
        get() = canAnswerInWords && session != null && draft.isNotBlank()

    /**
     * Whether a quick-reply chip does anything — [canSend] without the
     * blank-draft term, since a chip's own text is never blank. The row
     * itself is hidden outright rather than merely dimmed while [card] is up
     * or [readOnly] (a device that may not send has no use for a chip that
     * would only be refused) — that decision lives in `ui/SessionScreen.kt`,
     * the same place [card] already decides whether to draw the row at all.
     */
    val canSendQuick: Boolean
        get() = idle(sending, answering, busy) && !readOnly && connected && session != null
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
 * @param quickReplies The chip row and the draft history — one instance per
 *   [dev.claudefleet.mobile.AppContainer], not one per screen, so a chip added
 *   on one session's screen is there the next time any session's screen opens
 *   and the history is one list across the whole device rather than siloed
 *   per session. Defaults to an instance over an in-memory, unread [Prefs] so
 *   every existing caller in this file's own tests keeps compiling without
 *   naming one; every real caller supplies the container's own instance.
 */
class SessionViewModel(
    private val sessionId: Long,
    private val fleet: FleetState,
    private val actions: SessionActions,
    private val scope: CoroutineScope,
    canSendPrompts: Boolean = true,
    private val clock: () -> Long = { epochSeconds() },
    val quickReplies: QuickReplies = QuickReplies(EphemeralPrefs, EphemeralQuickReplies),
    private val drafts: DraftMemory = DraftMemory(),
    /**
     * The New bar (redesign 14.5): a prompt the hub refuses stays in the
     * conversation as [SessionUiState.notSent] instead of going back into the
     * box. False keeps the Classic rule — the words return to the box.
     */
    private val keepNotSent: Boolean = false,
    /**
     * `new_bg_session`, for ⋮ Background agent… (MobileFormsSession): an agent
     * started from inside this session, on its host and in its project. Null
     * where the app has not handed one over; the menu then offers nothing.
     */
    private val background: NewSessionActions? = null,
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
        val unseen: Int = 0,
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
        val conversations: List<ConversationSummary> = emptyList(),
        val viewing: ConversationSummary? = null,
        /** The earlier conversation's turns while [viewing]; the current one keeps updating underneath. */
        val earlier: Conversation? = null,
        /** The widest window has been read: there is nothing older to ask for. */
        val olderLoaded: Boolean = false,
        val loadingOlder: Boolean = false,
        val repair: RepairReport? = null,
        val pending: String? = null,
        /**
         * The failure a send raised, by identity: the composer draws [error]
         * only while it is still this one, so a later read's failure never
         * lands by the box.
         */
        val sendError: Friendly? = null,
        val notSent: NotSent? = null,
        val queued: List<QueuedPrompt> = emptyList(),
        val sendLaterNotice: String? = null,
        val switchTarget: HostLogin? = null,
        val waitingUntil: Long? = null,
        val limitNotice: String? = null,
    )

    private val local = MutableStateFlow(Local(draft = drafts.recall(sessionId)))
    private val tokenReadOnly = !canSendPrompts

    /**
     * No prompt from this screen: the credential's `readonly`, or a share
     * below drive (redesign 11.10). Read live, so a narrow that lands while
     * the screen is open darkens Send at once.
     */
    private val readOnly: Boolean
        get() = readOnlyFor(shareNow())

    private fun readOnlyFor(share: String?): Boolean =
        tokenReadOnly || share == GrantLevel.WATCH || share == GrantLevel.ANSWER

    private fun mayPressKeysFor(share: String?): Boolean = !tokenReadOnly && share != GrantLevel.WATCH

    /** The level this session is shared with this person at, or null when it is theirs or nothing is known. */
    private fun shareNow(): String? = fleet.access.value.levelFor(row())

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

    /**
     * The row's `turn_seq` as of the last conversation read this screen
     * applied. `null` until the first read lands, so nothing is ever skipped
     * before there is a drawn state to compare against.
     */
    private var drawnTurnSeq: Long? = null

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
        combine(fleet.sessions, fleet.status, fleet.hubVersion, local, combine(probe, now, fleet.access, ::Triple)) { rows, status, version, l, (probed, nowSeconds, access) ->
            assemble(rows.firstOrNull { it.id == sessionId }, status, version, l, probed, nowSeconds, access)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            assemble(row(), fleet.status.value, fleet.hubVersion.value, local.value, probe.value, now.value, fleet.access.value),
        )

    init {
        // Every change to the box, however it came — typing, a quote, a send
        // clearing it, a failure putting it back — is what a return finds.
        scope.launch {
            local.map { it.draft }.distinctUntilChanged().collect { drafts.keep(sessionId, it) }
        }
        // A draft filled from outside the box while it is open (a lesson's prompt).
        scope.launch {
            drafts.filled.filter { it == sessionId }.collect { local.update { l -> l.copy(draft = drafts.recall(sessionId)) } }
        }
        // Done · unread (contract 11): while this screen is open the session
        // is on screen, so every turn that ends here is seen. Stamped on the
        // hub (`touch_session_viewed`) only when the row reads unread — a
        // call per finished turn, not per frame — and only by someone who may
        // drive it: a watcher looking must not clear what the owner has not
        // seen, which the hub refuses anyway.
        scope.launch {
            fleet.sessions
                .map { rows -> rows.firstOrNull { it.id == sessionId }?.takeIf { it.isUnread }?.lastStopAt }
                .distinctUntilChanged()
                .filter { it != null }
                .collect { touchViewed() }
        }
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
                .filter { it == ALL_SESSIONS_CHANGED || rowChangeCouldMoveTheConversation() }
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
    fun load(): Job = scope.launch {
        // The chip row is fleet state, so opening a screen is when this phone
        // finds out about a chip written on the desktop (or on another
        // phone). Launched beside the read rather than awaited inside it: the
        // conversation must not wait on a row of buttons, and the cached row
        // is already drawn.
        refreshQuickReplies()
        loadConversations()
        requestRead(first = true)
    }

    /**
     * Pull the fleet's chip row. Silent on failure by design — the cached row
     * stays up, and a banner about buttons over a conversation that loaded
     * fine would be noise. An edit is the opposite case: see [editChips].
     */
    fun refreshQuickReplies(): Job = scope.launch {
        // One tool reads and writes the list, so the hub classifies it as a
        // write and hides it from a readonly token — which is also the token
        // whose screen draws no chip row at all. Not calling it is the app's
        // standing rule (only tools this token may use), not an optimisation.
        // An older hub has no such tool either; `HubCapabilities` says so.
        if (readOnly || !fleet.capabilities.value.quickReplies) return@launch
        try {
            quickReplies.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Cached chips stay on screen.
        }
    }

    /** Save a new chip — the draft, or one written in the edit sheet. */
    fun addQuickReply(chip: QuickReply): Job = editChips { quickReplies.add(chip) }

    /** Drop a chip from the fleet's row. */
    fun removeQuickReply(chip: QuickReply): Job = editChips { quickReplies.remove(chip) }

    /** Edit a chip in place — label, text, or both. */
    fun editQuickReply(original: QuickReply, edited: QuickReply): Job =
        editChips { quickReplies.replace(original, edited) }

    /** Move a chip one place left (-1) or right (1) in the fleet's row. */
    fun moveQuickReply(chip: QuickReply, by: Int): Job = editChips { quickReplies.move(chip, by) }

    /**
     * The one path a chip edit takes to the hub.
     *
     * Unlike [refreshQuickReplies] this reports: an edit is something the
     * person just did, [QuickReplies] has already put the row back the way it
     * was, and a chip that silently reappears after being deleted is the kind
     * of thing that gets filed as "the buttons cannot be edited".
     */
    private fun editChips(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        }
    }

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
        local.update {
            it.copy(
                atBottom = atBottom,
                newReply = if (atBottom) false else it.newReply,
                unseen = if (atBottom) 0 else it.unseen,
            )
        }
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
        if (!canAnswerNow(local.value, keys = a.isKey)) return@launch
        local.update { it.copy(answering = true, stillWaiting = false, error = null) }
        try {
            // Every KEY is pressed only after re-reading the pane. The hub
            // refuses typed text into a blocked session (E_INVALID_STATE) and
            // pasted text would reach a dialog as ESC first and cancel it, so
            // an answer has to be a key — and `send_prompt { keys }` has no
            // state gate on the hub at all. The row the card was drawn from is
            // up to a reconcile tick old, so nothing goes out unless the pane
            // still shows the same card. The same check the desktop's answer
            // card makes.
            //
            // Enter and Escape need this as much as the digits do, and on a
            // trust prompt more: Enter trusts the folder, and on a permission
            // dialog it picks the highlighted option, i.e. approves.
            val keyToPress = when (a) {
                is Answer.Option -> a.n.toString()
                Answer.Enter -> "Enter"
                Answer.Escape -> "Escape"
                // Drawn by no card (`blockedCard` never offers it), so it
                // cannot be pressed from a stale one; C-c interrupts rather
                // than approving anything.
                Answer.Interrupt -> null
                is Answer.Text -> null
            }
            if (keyToPress != null) {
                val asked = row()
                val moved = dialogMoved(
                    asked?.pendingInput,
                    asked?.stuckKind,
                    actions.activity(sessionId),
                    a as? Answer.Option,
                )
                if (moved != null) {
                    local.update { it.copy(answering = false, error = moved) }
                    return@launch
                }
            }
            val receipt = when (a) {
                is Answer.Option -> actions.sendKeys(sessionId, a.n.toString())
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
     * Answer the question up now in the person's own words — the draft —
     * rather than with one of its buttons (redesign 14.4).
     *
     * Two steps, each as [answer] and [send] already make them: press the
     * question's own no-and-say-why answer ([declineOption]) after re-reading
     * the pane, wait for the agent to take it, then send the draft as the next
     * prompt. Never an answer that allows: a question without a "No, …" answer
     * is not answered this way at all. A wait that times out sends nothing and
     * keeps the words in the box; the card says it is still waiting, and once
     * it is gone Send sends them as an ordinary prompt.
     */
    fun answerInWords(): Job = scope.launch {
        val current = local.value
        if (current.draft.isBlank() || !canAnswerNow(current, keys = false)) return@launch
        val asked = row() ?: return@launch
        val no = blockedCard(asked, fleet.hubVersion.value)?.let(::declineOption) ?: return@launch
        local.update { it.copy(answering = true, stillWaiting = false, error = null) }
        try {
            val moved = dialogMoved(asked.pendingInput, asked.stuckKind, actions.activity(sessionId), no)
            if (moved != null) {
                local.update { it.copy(answering = false, error = moved) }
                return@launch
            }
            val receipt = actions.sendKeys(sessionId, no.n.toString())
            val wait = actions.waitForTurn(sessionId, receipt.turnSeqBefore, timeoutS = ANSWER_WAIT_SECONDS)
            if (wait.status != WAIT_SATISFIED) {
                local.update { it.copy(answering = false, stillWaiting = true) }
                requestRead(first = false)
                return@launch
            }
            local.update { it.copy(answering = false) }
            // What is in the box now: the person may have edited it while the agent took the no.
            val words = local.value.draft.ifBlank { current.draft }
            deliver(words, clearDraft = true)
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

    /**
     * Send one of the [quickReplies] chips — the row [SessionUiState.card]
     * and [SessionUiState.readOnly] hide it for, drawn by `ui/SessionScreen.kt`.
     * Identical to [sendCommand] in every guard and effect — reused rather
     * than re-implemented, per the one-write-path rule [deliver] exists to
     * keep — and kept as its own name only so a chip tap reads as its own
     * intent in the call sites that fire it, not as a coincidental reuse of
     * the status strip's `/compact` call.
     */
    fun sendQuick(text: String): Job = sendCommand(text)

    /**
     * Press Enter in the session's pane (`send_prompt { keys }`) — the
     * desktop's ⏎ chip, for a REPL waiting on a bare Enter. Guarded like a
     * chip; nothing is typed, so the draft and the history are untouched.
     */
    fun pressEnter(): Job = scope.launch {
        val current = local.value
        // Not into a dialog: there Enter approves, and the card's own Enter
        // re-reads the pane first.
        if (!canWriteNow(current) || row() == null || blockedNow()) return@launch
        local.update { it.copy(sending = true, error = null) }
        try {
            actions.sendKeys(sessionId, "Enter")
            local.update { it.copy(sending = false) }
            requestRead(first = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(sending = false, error = friendly(t)) }
        }
    }

    /**
     * One key of the full-screen agent's bar (redesign 14.21) pressed in the
     * pane while nothing is asked: Escape, Tab, Enter or C-c, and the arrows,
     * ⇧Tab and Ctrl letters where the hub lists them (14.14,
     * [HubCapabilities.paneKeys]); nothing else.
     * Guarded as [pressEnter] is — never into a question, whose keys go
     * through [answer] — and the pane is read again after it, since a key
     * such as Tab changes the screen without a turn the hub would announce.
     */
    fun pressKey(key: String): Job = scope.launch {
        val current = local.value
        if (key !in fleet.capabilities.value.paneKeys || !canWriteNow(current) || row() == null || blockedNow()) return@launch
        local.update { it.copy(sending = true, error = null) }
        try {
            actions.sendKeys(sessionId, key)
            local.update { it.copy(sending = false) }
            delay(AFTER_KEY_MS)
            if (local.value.terminalShown) captureTerminal()
            requestRead(first = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(sending = false, error = friendly(t)) }
        }
    }

    /**
     * The guarded hub write [send], [sendCommand] and [sendQuick] all make,
     * and the refetch that follows it — the one write path, per the task-6
     * ruling that added [sendQuick] rather than a second send.
     *
     * [QuickReplies.remember] is called here, once, rather than in each of
     * the three callers: this is the one place a prompt from the composer (or
     * a chip, or the status strip) is known to have actually been *accepted*
     * by the hub — after [SessionActions.sendPrompt] returns and before
     * anything here can still fail. A card's own [answer] never reaches this
     * function (it calls [SessionActions.sendPrompt]/[SessionActions.sendKeys]
     * directly), so neither a key nor a typed answer is ever recorded here —
     * this is a history of what was composed, not of every prompt this screen
     * ever sent.
     */
    private suspend fun deliver(text: String, clearDraft: Boolean, keep: Boolean = keepNotSent && clearDraft) {
        // Out of the box at once and shown as pending: the box is free and the
        // prompt is visibly on its way, rather than both frozen until the hub
        // answers. A failure puts it back.
        local.update {
            it.copy(
                sending = true,
                error = null,
                pending = text,
                draft = if (clearDraft) "" else it.draft,
            )
        }
        try {
            actions.sendPrompt(sessionId, text)
            quickReplies.remember(text)
            // What was sent lands in the current conversation: show that one.
            local.update { it.copy(sending = false, pending = null, viewing = null, earlier = null) }
            requestRead(first = false)
        } catch (e: CancellationException) {
            local.update { it.copy(sending = false, pending = null, draft = if (clearDraft && it.draft.isEmpty()) text else it.draft) }
            throw e
        } catch (t: Throwable) {
            val failure = friendly(t)
            if (keep) {
                // The New bar: kept in the conversation, marked, with Retry and Edit.
                local.update { it.copy(sending = false, pending = null, notSent = NotSent(text, failure)) }
                return
            }
            local.update {
                it.copy(
                    sending = false,
                    pending = null,
                    // Back in the box, unless something new was typed meanwhile.
                    draft = if (clearDraft && it.draft.isEmpty()) text else it.draft,
                    error = failure,
                    sendError = failure,
                )
            }
        }
    }

    /**
     * Send the prompt the hub did not take again (redesign 14.5). Guarded like
     * a chip — the box may hold something else meanwhile, and that stays. A
     * second refusal keeps the words in the conversation again.
     */
    fun retryNotSent(): Job = scope.launch {
        val current = local.value
        val unsent = current.notSent ?: return@launch
        if (!canWriteNow(current) || row() == null || blockedNow()) return@launch
        local.update { it.copy(notSent = null) }
        deliver(unsent.text, clearDraft = false, keep = true)
    }

    /** Put the prompt the hub did not take back into the box, after what is typed, to edit it; nothing is sent. */
    fun editNotSent() {
        local.update { l ->
            val unsent = l.notSent ?: return@update l
            l.copy(notSent = null, draft = appendToDraft(l.draft, unsent.text))
        }
    }

    /**
     * "Retry the last turn" on a failed session (redesign 14.5): send its last
     * prompt again as a new one, through the one write path. Nothing is
     * rewound; a refusal is kept as [SessionUiState.notSent] on the New bar.
     */
    fun retryLastTurn(prompt: String): Job = scope.launch {
        val current = local.value
        if (prompt.isBlank() || !canWriteNow(current) || row() == null || blockedNow()) return@launch
        deliver(prompt, clearDraft = false, keep = keepNotSent)
    }

    /**
     * Stop the working agent: Escape in its pane (`send_prompt { keys }`) —
     * Claude's own interrupt, which unlike Ctrl-C never quits the REPL on a
     * second press — and the phone's only way to stop a turn heading the
     * wrong way short of killing the session. Never into a dialog.
     */
    fun interrupt(): Job = scope.launch {
        val current = local.value
        if (!canWriteNow(current) || blockedNow() || row()?.claudeStatus != "working") return@launch
        local.update { it.copy(sending = true, error = null) }
        try {
            actions.sendKeys(sessionId, "Escape")
            local.update { it.copy(sending = false) }
            requestRead(first = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val failure = friendly(t)
            local.update { it.copy(sending = false, error = failure, sendError = failure) }
        }
    }

    /**
     * Kill and recreate the tmux session in place — for a wedged REPL. Also
     * what the blocked card's own Restart button calls (see
     * [SessionUiState.canRestart]) when it draws one at all.
     */
    fun restart(): Job = runManaged(::canRestartNow) { actions.restart(sessionId) }

    // ---- Send later (redesign 14.14, claude-fleet 5.10 deferred prompts) ----

    /**
     * Hand [text] to the hub to type as a new turn the next time the session
     * is idle — or now, if it is idle already. The hub holds it, so it goes
     * whether or not this phone is still running; it is never typed into a
     * dialog. Only where the hub serves `queue_prompt` and this person may
     * drive the session.
     */
    fun sendLater(text: String): Job = scope.launch {
        val body = text.trim()
        if (body.isEmpty() || !fleet.capabilities.value.sendLater || readOnly || row() == null || !connected()) return@launch
        if (!idle(local.value.sending, local.value.answering, local.value.busy)) return@launch
        local.update { it.copy(busy = true, error = null, sendLaterNotice = null) }
        try {
            val r = actions.queuePrompt(sessionId, body)
            val notice = if (r.delivered) "Sent now: the session was idle." else "It goes in when the session is next idle."
            val queued = runCatching { actions.queuedPrompts(sessionId) }.getOrNull()
            local.update { it.copy(sendLaterNotice = notice, queued = queued?.filter { q -> q.waiting } ?: it.queued) }
            if (r.delivered) requestRead(first = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        } finally {
            local.update { it.copy(busy = false) }
        }
    }

    private suspend fun touchViewed() {
        if (!fleet.capabilities.value.touchViewed || readOnly) return
        try {
            actions.touchViewed(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A stamp is bookkeeping: a refusal or a dropped call costs one
            // row staying unread, never a banner.
        }
    }

    /** Read what the hub keeps for this session, for the Send later sheet. Silent on failure. */
    fun loadQueued(): Job = scope.launch {
        if (!fleet.capabilities.value.sendLater || readOnly) return@launch
        try {
            val queued = actions.queuedPrompts(sessionId)
            local.update { it.copy(queued = queued.filter { q -> q.waiting }) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    /** Take a waiting prompt back before the session gets it. */
    fun cancelQueued(id: Long): Job = scope.launch {
        if (!fleet.capabilities.value.sendLater || readOnly || !connected()) return@launch
        try {
            val left = actions.queuedPrompts(sessionId, cancel = id)
            local.update { it.copy(queued = left.filter { q -> q.waiting }, sendLaterNotice = "Taken back.") }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        }
    }

    fun dismissSendLaterNotice() {
        local.update { it.copy(sendLaterNotice = null) }
    }

    // ---- a paused-on-limit row: Switch account and Wait (step 4.10) ----

    /**
     * Switch account, first tap: ask the hub which login on the session's
     * host has the most room on another account, and show it. Nothing moves
     * until [confirmSwitch] — AI proposes, a person confirms.
     */
    fun proposeSwitch(): Job = scope.launch {
        val r = row() ?: return@launch
        if (!fleet.capabilities.value.switchAccount || !canRestartNow() || !connected()) return@launch
        if (!idle(local.value.sending, local.value.answering, local.value.busy)) return@launch
        local.update { it.copy(busy = true, error = null, limitNotice = null, switchTarget = null) }
        try {
            val h = actions.accountHeadroom(r.hostAlias, r.claudeProfile).forAccount(r.accountUuid)
            val target = h.suggestion
            local.update {
                if (target != null) it.copy(switchTarget = target)
                else it.copy(limitNotice = "No other login on ${r.hostAlias} has room left.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        } finally {
            local.update { it.copy(busy = false) }
        }
    }

    /** Switch account, second tap: restart the session under the proposed login, resuming its conversation. */
    fun confirmSwitch(accountName: (String) -> String? = { null }): Job {
        val target = local.value.switchTarget
        return runManagedThen({ target != null && canRestartNow() && fleet.capabilities.value.switchAccount }) {
            val t = target ?: return@runManagedThen null
            actions.restartUnder(sessionId, t.profile ?: "")
            local.update { it.copy(switchTarget = null, limitNotice = "Resumed under ${loginLabel(t, accountName)}.") }
            null
        }
    }

    fun cancelSwitch() {
        local.update { it.copy(switchTarget = null) }
    }

    /** Wait: fold the buttons into "Waiting until <reset>" — nothing is sent; the session resumes on its own after the reset. */
    fun waitForReset(resetsAt: Long) {
        local.update { it.copy(waitingUntil = resetsAt, switchTarget = null) }
    }

    /**
     * Read the session's conversations (`session_conversations`), for the
     * header's picker. Silent on failure: the picker simply is not offered.
     */
    fun loadConversations(): Job = scope.launch {
        if (refused() || !fleet.capabilities.value.conversations) return@launch
        try {
            val list = actions.conversations(sessionId)
            local.update { it.copy(conversations = list) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    /**
     * Show [summary] instead of the current conversation — or go back to the
     * current one when [summary] is it. An earlier conversation is read once,
     * as wide as the hub allows; it does not move.
     */
    fun view(summary: ConversationSummary): Job = scope.launch {
        if (summary.current) {
            backToCurrent()
            return@launch
        }
        local.update { it.copy(viewing = summary, earlier = Conversation(), loadingOlder = true, error = null) }
        try {
            val read = actions.conversation(sessionId, turns = OLDER_TURNS, claudeSessionId = summary.claudeSessionId)
            local.update { if (it.viewing == summary) it.copy(earlier = read, loadingOlder = false) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { if (it.viewing == summary) it.copy(viewing = null, earlier = null, loadingOlder = false, error = friendly(t)) else it }
        }
    }

    /** Back to the conversation the session is in now. */
    fun backToCurrent() {
        local.update { it.copy(viewing = null, earlier = null, loadingOlder = false) }
    }

    /**
     * The current conversation's older turns: one read as wide as the hub
     * allows ([OLDER_TURNS]), which takes the place of the window on screen —
     * it holds every turn the screen had, and the ones before them.
     */
    fun loadOlder(): Job = scope.launch {
        val l = local.value
        if (refused() || l.loadingOlder || l.viewing != null) return@launch
        local.update { it.copy(loadingOlder = true, error = null) }
        try {
            fetchLock.withLock {
                val wide = actions.conversation(sessionId, turns = OLDER_TURNS)
                local.update {
                    it.copy(
                        conversation = if (wide.turns.size >= it.conversation.turns.size) wide else it.conversation,
                        olderLoaded = true,
                        loadingOlder = false,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(loadingOlder = false, error = friendly(t)) }
        }
    }

    /**
     * Rewind here: restart this session on its transcript cut before
     * [anchorUuid] — the turn's own prompt and everything after it go. The
     * original transcript is kept by the hub; the screen starts over on the
     * new conversation rather than merging it into the old one.
     */
    fun rewind(anchorUuid: String): Job = runManaged(::canManageNow) {
        actions.rewind(sessionId, anchorUuid, MODE_REWIND)
        startOver()
    }

    /**
     * Retry: [rewind] to before [prompt], then send [prompt] again once the
     * respawned REPL is back at its input ([awaitReplReady]). A REPL that
     * does not come back in time, or a send that fails, leaves [prompt] in
     * the composer with the reason, so it is never silently lost.
     */
    fun retry(anchorUuid: String, prompt: String): Job = runManagedThen(::canManageNow) {
        actions.rewind(sessionId, anchorUuid, MODE_REWIND)
        startOver()
        if (!awaitReplReady()) {
            local.update { it.copy(draft = prompt) }
            return@runManagedThen RETRY_NOT_READY
        }
        try {
            actions.sendPrompt(sessionId, prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(draft = prompt) }
            throw t
        }
        null
    }

    /**
     * Fork here: a new session on a copy of this one's transcript kept
     * through [anchorUuid] (null keeps all of it), in a new worktree named
     * [newWorktree] when given. This session is left alone; [onForked] is
     * handed the new session's id to open.
     */
    fun fork(anchorUuid: String?, newWorktree: String?, onForked: (Long) -> Unit): Job =
        runManaged(::canManageNow) {
            val row = actions.rewind(sessionId, anchorUuid, MODE_FORK, newWorktree)
            onForked(row.id)
        }

    /** After a rewind the session is on a new conversation: drop the old one rather than append to it. */
    private fun startOver() {
        drawnTurnSeq = null
        local.update {
            it.copy(conversation = Conversation(), loaded = false, newReply = false, unseen = 0, olderLoaded = false, viewing = null, earlier = null)
        }
    }

    /**
     * Poll `session_activity` until the REPL reads [isReplReady] on
     * [READY_STREAK] probes in a row, for at most [READY_TIMEOUT_MS]. A probe
     * that fails is not readiness: it resets the streak.
     */
    private suspend fun awaitReplReady(): Boolean {
        var streak = 0
        var waited = 0L
        while (true) {
            delay(READY_POLL_MS)
            waited += READY_POLL_MS
            val ready = try {
                isReplReady(actions.activity(sessionId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
            streak = if (ready) streak + 1 else 0
            if (streak >= READY_STREAK) return true
            if (waited >= READY_TIMEOUT_MS) return false
        }
    }

    /**
     * A review session in this one's worktree, seeded with [prompt]; [onStarted]
     * is handed its id to open.
     */
    fun spawnReview(prompt: String, onStarted: (Long) -> Unit): Job = runManaged(::canManageNow) {
        val row = actions.spawnReview(sessionId, prompt)
        onStarted(row.id)
    }

    /** Repair the workspace; what was found and done is shown until [dismissRepair]. */
    fun repair(): Job = runManagedThen(::canManageNow) {
        val report = actions.repair(sessionId)
        local.update { it.copy(repair = report) }
        null
    }

    fun dismissRepair() {
        local.update { it.copy(repair = null) }
    }

    /**
     * Kill and rebuild the session in its worktree, resuming the same
     * conversation — for a wedged REPL, or to bring a ghost back.
     */
    fun recreate(): Job = runManaged(::canManageNow) {
        actions.recreate(sessionId)
    }

    /** Let a ghost go: its row is deleted, and [onGone] takes the screen away. */
    fun dismissGhost(onGone: () -> Unit): Job = runManaged({ canManageNow() && row()?.lostAt != null }) {
        actions.dismissGhost(sessionId)
        onGone()
    }

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
     * The rename-and-tags sheet's one Save: [name] and [tags] are what
     * changed ([sessionEdit]), null for what did not. Both go in ONE managed
     * call — two would refuse the second, which [runManaged] does while the
     * first's refetch is still out.
     */
    fun edit(name: String?, tags: List<String>?): Job =
        runManaged({ canManageNow() && (name != null || tags != null) }) {
            if (name != null) actions.rename(sessionId, name)
            if (tags != null) actions.setTags(sessionId, tags)
        }

    /**
     * ⋮ Background agent…: a headless agent on this session's host, in its
     * project, started on [prompt] (`new_bg_session`). The screen stays where
     * it is: the agent runs without a pane and its result lands in Inbox,
     * which the banner says, with the hub's own warning behind Details.
     */
    fun startBackground(name: String, prompt: String, options: BackgroundOptions = BackgroundOptions()): Job =
        runManagedThen({ canManageNow() && background != null && fleet.capabilities.value.newBgSession && prompt.isNotBlank() }) {
            val start = background ?: return@runManagedThen null
            val r = row() ?: return@runManagedThen null
            val shownName = name.trim().ifEmpty { prompt.trim().take(40) }
            val result = if (fleet.capabilities.value.accepts(HubCapabilities.NEW_BG_SESSION, "read_only")) {
                start.newBackground(r.hostAlias, shownName, prompt.trim(), options.copy(projectId = options.projectId ?: r.projectId))
            } else {
                start.newBackground(r.hostAlias, shownName, prompt.trim())
            }
            Friendly(
                title = "Background agent started",
                body = "It runs without a pane on ${r.hostAlias}; the result lands in Inbox.",
                isError = false,
                details = result.warning,
            )
        }

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
    private fun runManaged(guard: () -> Boolean, call: suspend () -> Unit): Job =
        runManagedThen(guard) { call(); null }

    /**
     * [runManaged] for a call that can succeed and still have something to
     * say: what [call] answers is put on the banner AFTER the follow-up read,
     * since a read that lands clears the banner (it is about reads and sends).
     */
    private fun runManagedThen(guard: () -> Boolean, call: suspend () -> Friendly?): Job = scope.launch {
        val l = local.value
        if (!guard() || !connected() || !idle(l.sending, l.answering, l.busy)) return@launch
        local.update { it.copy(busy = true, error = null) }
        try {
            val after = call()
            requestRead(first = false)
            if (after != null) local.update { it.copy(error = after) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        } finally {
            local.update { it.copy(busy = false) }
        }
    }

    /** [SessionUiState.canManage] read from the live sources — see [runManaged]. */
    private fun canManageNow(): Boolean = !readOnly && shareNow() == null && row()?.isController == false

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
    private fun canAnswerNow(l: Local, keys: Boolean): Boolean =
        (if (keys) mayPressKeysFor(shareNow()) else !readOnly) && idle(l.sending, l.answering, l.busy) && connected()

    /** The part of [canSendNow] that does not care what is being sent — shared with [sendCommand]. */
    private fun canWriteNow(l: Local): Boolean = idle(l.sending, l.answering, l.busy) && !readOnly && connected()

    private fun canSendNow(l: Local): Boolean =
        canWriteNow(l) && l.draft.isNotBlank() && row() != null && !blockedNow()

    /**
     * The row draws a card — the session is waiting on a dialog or stuck.
     * The hub refuses typed text into a blocked session, and a bare Enter
     * would answer the dialog unchecked: both go through the card instead.
     */
    private fun blockedNow(): Boolean = row()?.let { blockedCard(it, fleet.hubVersion.value) } != null

    /** [isConnected] read from the live sources, for [send]'s own check. */
    private fun connected(): Boolean = isConnected(fleet.status.value, probe.value)

    private fun row(): SessionRow? = fleet.sessions.value.firstOrNull { it.id == sessionId }

    /**
     * Whether the row change that just arrived could have moved the
     * conversation — and so is worth a `session_conversation` read.
     *
     * A session row moves for reasons the transcript knows nothing about: a
     * tag, a CI result, a usage figure, and above all reconcile rewriting
     * `current_activity` on every pass. Reading the whole window back for
     * those is the largest call this app makes, spent on nothing.
     *
     * The rule is deliberately generous, and it is worth saying why the
     * obvious stricter one is wrong. Gating on `turn_seq` alone — "refetch
     * only when a turn completed" — would be silent for the entire length of
     * a reply, because `turn_seq` does not move until the turn ENDS. The
     * screen would sit still exactly while the answer is being written,
     * which is the one moment somebody is watching it.
     *
     * So a read happens whenever the turn count differs from the one the
     * screen was drawn against, OR a turn is in flight (`working`), OR the
     * session is waiting on the person (`blocked` — the question is in the
     * transcript). What is skipped is a row that moved while the session is
     * idle, done, stopped or failed and its turn count did not change: there
     * is nothing new to read.
     *
     * A `/clear`, a resume or a compaction switches the conversation under
     * us. The row does carry `claude_session_id`, but this app's model does
     * not — no screen draws it. It is covered anyway: a new conversation
     * starts its turn count over, so the count differs from the drawn one and
     * the read happens. `!=`, not `>`, for exactly that reason.
     */
    private fun rowChangeCouldMoveTheConversation(): Boolean {
        val row = row() ?: return true
        if (row.turnSeq != drawnTurnSeq) return true
        return row.claudeStatus == "working" || row.claudeStatus == "blocked"
    }

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
                    // The identity test cannot currently fail, and it stays.
                    // `queued` is only ever set to a new generation when it is
                    // null, and only this line sets it back to null, so from
                    // the moment this generation was registered until here it
                    // is either this generation or nothing. The guard is what
                    // makes that argument local instead of something a reader
                    // has to reconstruct from the other two call sites.
                    if (queued === generation) queued = null
                    running = generation
                }
                // The cursor the last applied read was drawn against, so the
                // hub sends what happened since instead of the last ten turns
                // every time. Null on the first read, which is the full
                // window — and `appending` folds a narrower one the same way,
                // since it is still a tail whose head overlaps what is held.
                val fresh = actions.conversation(sessionId, sinceTurn = drawnTurnSeq)
                // Stamped from the row as it is NOW, inside the lock and
                // before the apply: the read reflects whatever the hub had
                // when it answered, and a turn that completes after this
                // point must still trigger the next read.
                drawnTurnSeq = row()?.turnSeq
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
                        unseen = if (current.atBottom) {
                            0
                        } else {
                            current.unseen + appended.turnsAddedAfter(current.conversation)
                        },
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
                // Unlike the one above, this identity test *is* reachable,
                // and only off the test scheduler. This runs after
                // `fetchLock` has been released, so on a real dispatcher a
                // waiting generation can take the lock and publish itself as
                // `running` on another thread before this line executes;
                // clearing unconditionally would then drop the flags for a
                // read that is still in flight, and the screen would stop
                // saying it is fetching while it is. `runTest`'s single
                // thread cannot produce that interleaving, which is why a
                // mutation of this line survives every test in the suite.
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
        access: MyAccess,
    ) = SessionUiState(
        session = row,
        conversation = l.earlier ?: l.conversation,
        loaded = l.loaded,
        draft = l.draft,
        loading = l.loading,
        refreshing = l.refreshing,
        sending = l.sending,
        readOnly = readOnlyFor(access.levelFor(row)),
        share = access.levelFor(row),
        mayPressKeys = mayPressKeysFor(access.levelFor(row)),
        connected = isConnected(status, probed),
        hubReachable = probed,
        streaming = status is ConnectionStatus.Connected,
        error = l.error,
        silent = l.silent,
        newReply = l.newReply,
        unseen = l.unseen,
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
        // Read rather than combined: the hub's tool list is learned once per
        // connection, before any row this screen could draw arrives.
        rewindAvailable = fleet.capabilities.value.rewind,
        backgroundAvailable = background != null && fleet.capabilities.value.newBgSession,
        backgroundOptions = fleet.capabilities.value.accepts(HubCapabilities.NEW_BG_SESSION, "read_only"),
        conversations = l.conversations,
        viewing = l.viewing,
        canLoadOlder = l.viewing == null && l.conversation.truncated && !l.olderLoaded &&
            l.conversation.turns.size < OLDER_TURNS,
        loadingOlder = l.loadingOlder,
        recreateAvailable = fleet.capabilities.value.recreateSession,
        dismissGhostAvailable = fleet.capabilities.value.dismissGhost,
        reviewAvailable = fleet.capabilities.value.spawnReview,
        repairAvailable = fleet.capabilities.value.repairSession,
        paneKeys = fleet.capabilities.value.paneKeys,
        repair = l.repair,
        pending = l.pending,
        errorFromSend = l.error != null && l.error === l.sendError,
        notSent = l.notSent,
        sendLaterAvailable = fleet.capabilities.value.sendLater,
        queued = l.queued,
        sendLaterNotice = l.sendLaterNotice,
        switchAccountAvailable = fleet.capabilities.value.switchAccount,
        switchTarget = l.switchTarget,
        // A wait holds for the limit it was chosen for: while its reset is
        // ahead. A later limit asks afresh rather than "Waiting until <past>".
        waitingUntil = l.waitingUntil?.takeIf { it > nowSeconds },
        limitNotice = l.limitNotice,
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

/** How long after a key the pane is read again: long enough for the agent to redraw. */
internal const val AFTER_KEY_MS: Long = 300L

/**
 * Why this answer must not be pressed, or null when it may: the fresh [probe]
 * has to show the same card the person tapped. The row is up to a reconcile
 * tick old, so without this a tap on a stale card could approve a permission
 * the person never saw — or press a digit into a REPL that has already moved
 * on. PURE, for the tests.
 *
 * Two card shapes, because [blockedCard] draws two and both offer keys:
 *
 *  - a STUCK card ([askedStuck] set — `trust_prompt`, `press_enter`, an
 *    unknown kind): the pane must still be stuck the same way. The hub
 *    reports such a pane as `stuck_kind` with `pending_input: null`, so the
 *    dialog identity below says nothing about it. This is the dangerous one:
 *    Enter on "Trust this folder?" trusts the folder, and on a permission
 *    dialog it picks the highlighted option, i.e. it approves — so a trust
 *    card left on screen after the pane moved to a permission dialog was one
 *    tap from an unauthorised, irreversible grant.
 *  - a DIALOG card ([asked] set): the pane must still be blocked on a dialog
 *    of the same identity, and — when [option] names one — still offer it.
 *    [option] is null for the bare Enter/Escape chips that sit beside the
 *    digits on that same card.
 */
internal fun dialogMoved(
    asked: PendingInput?,
    askedStuck: String?,
    probe: ActivityProbe,
    option: Answer.Option?,
): Friendly? {
    val gone = Friendly(
        "That question is gone",
        "Nothing was sent — it was answered or dismissed already.",
        isError = false,
    )
    val changed = Friendly(
        "The question changed",
        "Nothing was sent — read it again and choose.",
        isError = false,
    )
    if (askedStuck != null) {
        return when (probe.stuckKind) {
            null -> gone
            askedStuck -> null
            else -> changed
        }
    }
    val onScreen = probe.pendingInput?.takeIf { probe.stuckKind == null && probe.claudeStatus == "blocked" }
    return when {
        onScreen == null -> gone
        asked == null || onScreen.fingerprint() != asked.fingerprint() -> changed
        option != null && onScreen.options.none { it.n == option.n && it.label == option.label } -> changed
        else -> null
    }
}

/** What `wait_for_session` answers when the turn actually moved. */
internal const val WAIT_SATISFIED: String = "satisfied"

/**
 * Backs the default [SessionViewModel.quickReplies] for a caller that names
 * no [QuickReplies] of its own. Every real caller does — see
 * [dev.claudefleet.mobile.AppContainer.quickReplies] — so nothing written
 * here is ever read back; it exists only so this file's own tests, and any
 * other construction that has no opinion about quick replies, keep compiling
 * without naming a [Prefs].
 */
private object EphemeralPrefs : Prefs {
    override fun getStringList(key: String): List<String> = emptyList()
    override fun putStringList(key: String, value: List<String>) = Unit
}

/**
 * The other half of that default: a [QuickReplyActions] with no hub behind
 * it, which simply echoes what it is handed (and reads an empty list). Same
 * reasoning as [EphemeralPrefs] — a construction with no opinion about quick
 * replies should not need a transport — and echoing rather than throwing
 * keeps an edit in such a construction a local no-op instead of an error the
 * caller never asked about.
 */
private object EphemeralQuickReplies : QuickReplyActions {
    override suspend fun quickReplies(set: List<QuickReply>?): List<QuickReply> = set ?: emptyList()
}

private const val MODE_REWIND = "rewind"
private const val MODE_FORK = "fork"

/** Retry's rewind landed but the REPL did not come back in time; the prompt waits in the composer. */
private val RETRY_NOT_READY = Friendly(
    "Rewound — the prompt was not sent again",
    "The session did not come back to its prompt in time. Your prompt is in the box; send it when the session is ready.",
    isError = true,
)

/** The widest window `session_conversation` answers: its `turns` maximum. */
internal const val OLDER_TURNS: Int = 100

/**
 * The desktop's review prompt (`DEFAULT_REVIEW_PROMPT` in `sessions.ts`), so a
 * review started from the phone asks for the same three passes.
 */
internal const val DEFAULT_REVIEW_PROMPT: String = """Review the work in this worktree. Run `git diff` and `git log` against the base branch to see what changed.

Pass 1 — correctness: does the code do what it should? Any bugs?
Pass 2 — code quality: clarity, structure, test coverage.
Pass 3 — risk: anything dangerous, security-sensitive, or destructive?

Cite file:line for every point. End with an overall verdict: approve / approve-with-fixes / needs-rework."""
