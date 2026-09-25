package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.model.withTicketsFrom
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_LINK
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.isUnknownAction
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

/** The ticket chip on a session screen, and the small sheet behind it. */
data class SessionWorkUiState(
    /** The session's confirmed work, or null. Only ever set when the hub has the work graph. */
    val work: WorkSummary? = null,
    /** The hub's undecided guess, or null. */
    val suggested: WorkSummary? = null,
    val sheetOpen: Boolean = false,
    /** Accept the guess (`work_link confirm`). */
    val canConfirm: Boolean = false,
    /** "Not this" (`work_link reject`). */
    val canReject: Boolean = false,
    /** Clear the confirmed link (`work_link unlink`). */
    val canClear: Boolean = false,
    /** The overflow menu's *Set work…* (`work_link link`). */
    val canSetWork: Boolean = false,
    val busy: Boolean = false,
    val error: Friendly? = null,
    /**
     * **Ask for a handover** (`work_link handover`, claude-fleet M9.3): a
     * write token, a hub that lists it, a session with a confirmed key, and a
     * running Claude session to ask. The hub refuses the rest (busy, stuck)
     * in words the sheet shows.
     */
    val canHandover: Boolean = false,
    /** Where the last handover asked from this screen has got to, or null. */
    val handover: HandoverStatus? = null,
) {
    /** What the chip draws: the confirmed work, else the guess. */
    val chip: WorkSummary? get() = work ?: suggested
}

/**
 * The work part of a session screen: what the hub says the session is on,
 * and the decisions a person may make about it.
 *
 * Its own class rather than more of `SessionViewModel`, which is about the
 * conversation; this one is small, reads [FleetState] for the row and the
 * hub's capabilities, and talks to [WorkActions] only.
 *
 * **Every button is gated twice**: on [canWrite] — the credential — and on
 * [FleetState.capabilities] listing `work_link` *and* the action. A readonly
 * token fails both; a hub before M4 fails the second for Confirm; a hub whose
 * `action` is a free string and that refuses one as unknown drops that action
 * for the rest of the connection ([FleetState.actionMissing]).
 *
 * A decision's reply is the updated row, but it is not written anywhere: the
 * hub's own `session:updated` frame is what moves the chip, so there is one
 * path by which a row changes on this phone.
 */
class SessionWorkViewModel(
    private val sessionId: Long,
    private val fleet: FleetState,
    private val actions: WorkActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val sheetOpen: Boolean = false,
        val busy: Boolean = false,
        val error: Friendly? = null,
        val handover: HandoverStatus? = null,
    )

    private val local = MutableStateFlow(Local())

    init {
        // The hub writes the note when the turn it asked for stops, and says
        // so on the session's timeline; the answer to the call only means the
        // prompt went in. Followed for the life of the screen, so a handover
        // asked on the desktop is reported here too.
        scope.launch {
            fleet.timeline.collect { frame ->
                if (frame.sessionId != sessionId) return@collect
                val status = HandoverStatus.of(frame.kind) ?: return@collect
                local.update { it.copy(handover = status) }
            }
        }
    }

    val state: StateFlow<SessionWorkUiState> =
        combine(fleet.sessions, fleet.capabilities, fleet.tickets, local) { rows, caps, cache, l ->
            assemble(rows.freshRow(cache), caps, l)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            assemble(fleet.sessions.value.freshRow(fleet.tickets.value), fleet.capabilities.value, local.value),
        )

    /** This session's row, its work refreshed from the ticket cache — the list does the same. */
    private fun List<SessionRow>.freshRow(cache: List<Ticket>): SessionRow? =
        firstOrNull { it.id == sessionId }?.withTicketsFrom(cache.associateBy { it.id })

    fun openSheet() {
        if (state.value.chip != null) local.update { it.copy(sheetOpen = true) }
    }

    fun closeSheet() {
        local.update { it.copy(sheetOpen = false) }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /** Accept the suggestion the chip is showing. */
    fun confirm(): Job = decide(CONFIRM) { actions.confirm(sessionId, it.linkId) }

    /** "Not this" — a sticky rejection: the hub never suggests it for this session again. */
    fun reject(): Job = decide(REJECT) { actions.reject(sessionId, it.linkId) }

    /** Clear the confirmed link. */
    fun clear(): Job = runGated(UNLINK) {
        val work = row()?.work ?: return@runGated
        actions.unlink(sessionId, work.linkId)
    }

    /**
     * *Set work…*: a key or a pasted ticket URL. Looked up first, so a
     * tracker's ticket is linked by its item (title, status and all); a key no
     * tracker knows is linked as a bare key — trackers never gate work.
     *
     * A URL is linked **only** by the item it resolves to. The hub takes any
     * string of up to 64 characters as a free-form key, so a URL sent as one
     * would make a work group named after the URL. When the hub cannot look
     * it up — no `lookup`, or a refusal — the person is asked for the key.
     *
     * The lookup's own refusal is answered here and never rethrown:
     * [runGated] would read an "unknown action" there as `work_link link`
     * being missing and hide *Set work…* for the rest of the connection,
     * when it was `work lookup` the hub did not know.
     */
    fun setWork(input: String): Job = runGated(LINK) {
        val reference = input.trim()
        if (reference.isEmpty()) return@runGated
        val isUrl = reference.contains("://")
        val ticket = if (fleet.capabilities.value.has(WORK, LOOKUP)) {
            try {
                actions.lookup(reference)
            } catch (e: HubError.Tool) {
                val unknown = e.isUnknownAction()
                if (unknown) fleet.actionMissing(WORK, LOOKUP)
                when {
                    // A bare key may stand on its own.
                    !isUrl && (unknown || e.code == "E_NOTFOUND") -> null
                    unknown -> return@runGated refuseUrl()
                    else -> {
                        local.update { it.copy(error = friendlyWork(e)) }
                        return@runGated
                    }
                }
            }
        } else {
            null
        }
        when {
            ticket != null -> actions.link(sessionId, itemId = ticket.id)
            isUrl -> refuseUrl()
            else -> actions.link(sessionId, key = reference)
        }
    }

    /**
     * **Ask for a handover**: the hub types a request into the idle session
     * and the note is written when that turn stops (see [HandoverStatus]).
     * Its refusals are said for what they mean here — not idle, one already
     * asked — rather than as a generic "the hub refused that".
     */
    fun handover(): Job = scope.launch {
        if (!state.value.canHandover || !allowed(fleet.capabilities.value, HANDOVER) || local.value.busy) return@launch
        local.update { it.copy(busy = true, error = null) }
        try {
            actions.handover(sessionId)
            // The timeline may already have moved past "asked" (a fast
            // turn); a later answer to the call must not move it back.
            local.update { l ->
                if (l.handover == null || l.handover == HandoverStatus.Requested) l.copy(handover = HandoverStatus.Requested) else l
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HubError.Tool) {
            if (e.isUnknownAction()) fleet.actionMissing(WORK_LINK, HANDOVER)
            local.update { it.copy(error = friendlyHandover(e)) }
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        } finally {
            local.update { it.copy(busy = false) }
        }
    }

    /** A URL the hub cannot resolve to a ticket: ask for the key rather than link the URL. */
    private fun refuseUrl() {
        local.update {
            it.copy(
                error = Friendly(
                    "This hub can't look up a ticket link",
                    "Type the ticket's key instead, like PAY-7.",
                    isError = true,
                ),
            )
        }
    }

    private fun decide(action: String, call: suspend (WorkSummary) -> Unit): Job = runGated(action) {
        val guess = row()?.workSuggested ?: return@runGated
        call(guess)
        local.update { it.copy(sheetOpen = false) }
    }

    /**
     * Re-checks the gate from the sources rather than from `state`, which is
     * one dispatch behind — a tap must never reach a tool the token may not
     * call, even in the frame before a button disappears.
     */
    private fun runGated(action: String, call: suspend () -> Unit): Job = scope.launch {
        if (!allowed(fleet.capabilities.value, action) || local.value.busy) return@launch
        local.update { it.copy(busy = true, error = null) }
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HubError.Tool) {
            if (e.isUnknownAction()) fleet.actionMissing(WORK_LINK, action)
            local.update { it.copy(error = friendlyWork(e)) }
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        } finally {
            local.update { it.copy(busy = false) }
        }
    }

    private fun allowed(caps: HubCapabilities, action: String): Boolean =
        canWrite && caps.work && caps.has(WORK_LINK, action)

    private fun row(): SessionRow? = fleet.sessions.value.firstOrNull { it.id == sessionId }

    private fun assemble(row: SessionRow?, caps: HubCapabilities, l: Local): SessionWorkUiState {
        // No work graph: nothing to draw, even if a row somehow carries one.
        if (row == null || !caps.work) return SessionWorkUiState(error = l.error)
        val work = row.work
        val guess = row.workSuggested
        return SessionWorkUiState(
            work = work,
            suggested = guess,
            sheetOpen = l.sheetOpen && (work != null || guess != null),
            canConfirm = guess != null && allowed(caps, CONFIRM),
            canReject = guess != null && allowed(caps, REJECT),
            canClear = work != null && allowed(caps, UNLINK),
            canSetWork = allowed(caps, LINK),
            busy = l.busy,
            error = l.error,
            canHandover = work?.key != null && row.canBeAskedForHandover && allowed(caps, HANDOVER),
            handover = l.handover,
        )
    }

    private companion object {
        const val CONFIRM = "confirm"
        const val REJECT = "reject"
        const val UNLINK = "unlink"
        const val LINK = "link"
        const val LOOKUP = "lookup"
        const val HANDOVER = "handover"
    }
}

/**
 * Where a handover asked for a session has got to, from the hub's timeline
 * (`handover_*` entries, claude-fleet M9.3).
 */
enum class HandoverStatus(val sentence: String) {
    Requested("Asked Claude for a handover. It is written when this turn ends."),
    Written("Handover written. It is in the work's context for whoever picks it up next."),
    Missing("Claude's reply had no handover in it. Ask again, or write one on the desktop."),
    SendFailed("The request could not be typed into the session."),
    ;

    companion object {
        fun of(kind: String): HandoverStatus? = when (kind) {
            "handover_requested" -> Requested
            "handover_written" -> Written
            "handover_missing" -> Missing
            "handover_send_failed" -> SendFailed
            else -> null
        }
    }
}

/**
 * Whether the session is one the hub could ask: running, and a Claude
 * session of fleet's own — not a background agent, a shell, or one running
 * outside fleet. The hub checks the rest (idle, not stuck) and says so.
 */
private val SessionRow.canBeAskedForHandover: Boolean
    get() = status == "running" && !isBackground && kind != "shell" && kind != "external"

/** A handover refusal, said for what it means on this sheet. */
internal fun friendlyHandover(e: HubError.Tool): Friendly {
    // An older hub's "unknown action" is an E_INVALID too, and means the hub,
    // not this session.
    if (e.isUnknownAction()) return friendlyWork(e)
    val raw = explain(e)
    return when (e.code) {
        "E_NOT_ALIVE" -> Friendly(
            "Claude can't write one right now",
            "Ask again when the session is idle — not mid-turn, blocked, or stuck.",
            isError = true,
            details = raw,
        )
        "E_EXISTS" -> Friendly("A handover is already on its way", e.message, isError = false, details = raw)
        "E_INVALID" -> Friendly("No handover for this session", e.message, isError = true, details = raw)
        else -> friendlyWork(e)
    }
}

/**
 * [friendly] for a work-graph refusal. The generic mapping reads `E_NOTFOUND`
 * as "this session is gone", which is wrong here: what was not found is a
 * ticket or a link, and the session is still on screen.
 */
internal fun friendlyWork(t: Throwable): Friendly {
    if (t !is HubError.Tool) return friendly(t)
    val raw = explain(t)
    return when {
        t.isUnknownAction() -> Friendly("This hub can't do that yet", "Update the hub to use it from the phone.", isError = true, details = raw)
        t.code == "E_NOTFOUND" -> Friendly("Not found", t.message, isError = true, details = raw)
        t.code == "E_INVALID" -> Friendly("The hub refused that", t.message, isError = true, details = raw)
        t.code == "E_EXISTS" -> Friendly("Already running", t.message, isError = false, details = raw)
        t.code == "E_AMBIGUOUS" -> Friendly("Pick one", t.message, isError = true, details = raw)
        else -> friendly(t)
    }
}

/**
 * Why a link exists, in plain words — the desktop's `linkWhy` / `describeWorkKey`
 * vocabulary, so the phone and the desktop explain a link the same way.
 */
fun workWhy(work: WorkSummary): String {
    val rule = work.rule?.takeIf { it.isNotBlank() }?.let { " · rule $it" } ?: ""
    // The classification nudge's answer (claude-fleet M4.6): Claude's own
    // pick when fleet asked it, only ever a suggestion until a person
    // confirms. The desktop's words, and no strength suffix — `inferred` is
    // said by the sentence itself.
    if (work.source == AGENT_INFERRED) {
        return (if (work.state == "suggested") "suggested" else "named") + " by Claude when asked" + rule
    }
    val from = when (work.source) {
        "manual" -> "set by hand"
        "agent" -> "set by the agent"
        "started" -> "started from the ticket"
        "branch" -> "linked from the branch"
        "pr" -> "linked from the pull request"
        "trailer" -> "linked from a commit trailer"
        "url" -> "linked from a ticket URL in a prompt"
        "prompt" -> "linked from a prompt"
        "" -> "linked"
        else -> "linked (${work.source})"
    }
    val strength = work.strength?.takeIf { work.state == "suggested" }?.let { " · $it guess" } ?: ""
    return from + rule + strength
}

/** The source of a link Claude named when fleet asked it to classify its session (M4.6, rule R11). */
internal const val AGENT_INFERRED = "agent_inferred"
