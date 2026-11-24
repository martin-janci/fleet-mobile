package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.model.AccountLimit
import dev.claudefleet.mobile.model.Headroom
import dev.claudefleet.mobile.model.HostLogin
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.MissionWait
import dev.claudefleet.mobile.model.GrantLevel
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.forAccount
import dev.claudefleet.mobile.model.signedInElsewhere
import dev.claudefleet.mobile.model.loginLabel
import dev.claudefleet.mobile.epochSeconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * The Inbox's inline answers (gap plan G5.4, board MobileNav): a failed row
 * carries Open log and Retry, a row paused at its account's limit carries
 * Switch account and Wait, a mission that waits on a person is a row of its
 * own, and Jev's "probably waiting" reading is a row with Not waiting. Each
 * press is the same call the session screen makes (Retry the last turn,
 * Switch account's two taps, Wait), so the Inbox answers nothing the session
 * would not.
 */

/** The calls the Inbox's rows make: the session screen's own, nothing else. */
interface InboxActions {
    /**
     * Retry the last turn: its prompt again, as a new one, under
     * [clientMsgId] (`send_prompt { client_msg_id }`), so a second press after
     * a lost answer is replayed by the hub rather than typed twice.
     */
    suspend fun retry(sessionId: Long, prompt: String, clientMsgId: String)

    /**
     * Which login on [hostAlias] has room left (`check_account_headroom`), for
     * work in [projectId] on [model] (the row's `launch_model`, review w10-5).
     */
    suspend fun headroom(hostAlias: String, profile: String?, projectId: Long? = null, model: String? = null): Headroom

    /** Restart the session under [profile], resuming its conversation. */
    suspend fun restartUnder(sessionId: Long, profile: String)
}

/** [InboxActions] through the session screen's [SessionActions]. */
class SessionInboxActions(private val actions: SessionActions) : InboxActions {
    override suspend fun retry(sessionId: Long, prompt: String, clientMsgId: String) {
        actions.sendPrompt(sessionId, prompt, clientMsgId)
    }

    override suspend fun headroom(hostAlias: String, profile: String?, projectId: Long?, model: String?): Headroom =
        actions.accountHeadroomFor(hostAlias, profile, projectId, model)

    override suspend fun restartUnder(sessionId: Long, profile: String) = actions.restartUnder(sessionId, profile)
}

/** What a Needs you row offers inline; see [inboxRowActions]. */
data class InboxRowActions(
    /** Open the session at its failure: the conversation with its error card. */
    val openLog: Boolean = false,
    /** The prompt Retry sends again; null when there is nothing to retry or no right to. */
    val retryPrompt: String? = null,
    val switchAccount: Boolean = false,
    /** The reset Wait waits for; null when no reading says when. */
    val waitUntil: Long? = null,
) {
    val any: Boolean get() = openLog || retryPrompt != null || switchAccount || waitUntil != null
}

/**
 * A Needs you row's inline answers. A failed or stuck row ([inboxWord]
 * Failed) opens its log; a failed one with a last prompt also retries it,
 * when this device may write and the row is not the controller's. A row
 * paused at its account's limit offers Switch account where the hub can say
 * which login has room ([switchAvailable]), and Wait when [limit] says when it
 * resets. Everything else answers in the session.
 */
fun inboxRowActions(
    row: SessionRow,
    canWrite: Boolean,
    switchAvailable: Boolean,
    limit: AccountLimit?,
    /**
     * The level the row is shared with this person at; null for their own.
     * The hub takes a prompt from Steer and a restart from the owner only, so
     * Retry is own or Steer and Switch account is own (review w05-2).
     */
    level: String? = null,
): InboxRowActions {
    val mayManage = canWrite && !row.isController
    val maySwitch = mayManage && level == null
    val mayPrompt = mayManage && (level == null || level == GrantLevel.DRIVE)
    return when {
        row.attention?.reason == "account_limit" -> InboxRowActions(
            switchAccount = maySwitch && switchAvailable,
            waitUntil = limit?.resetsAt,
        )
        // A signed-out login: resume under one that is signed in (review w23-4).
        row.attention?.reason == "no_credentials" -> InboxRowActions(switchAccount = maySwitch && switchAvailable)
        inboxWord(row) == dev.claudefleet.mobile.ui.kit.StatusWord.FAILED -> InboxRowActions(
            openLog = true,
            retryPrompt = row.lastPrompt?.trim()?.takeIf {
                it.isNotEmpty() && mayPrompt && row.claudeStatus == "failed" && row.pendingInput == null
            },
        )
        else -> InboxRowActions()
    }
}

/**
 * The missions waiting on a person (contract 15's `waiting_on`, the
 * desktop's `waitingOf`), the longest waiting first. The hub's `waiting_on`
 * decides: a mission a brake paused with an open question waits too, not
 * only an active one (review w09-1).
 */
fun missionWaits(missions: List<Mission>): List<Mission> =
    missions.filter { it.waitingOn != null }
        .sortedWith(compareBy<Mission> { it.waitingOn?.since ?: 0 }.thenBy { it.id })

/**
 * What a mission waits for a person to do, as a verb ("sign the autonomy
 * grant"), the desktop's `waitWords`: a mission row's line and the
 * notification's "Mission waits for you to …". [missionWaitLabel] is the noun form.
 */
fun missionAskWords(w: MissionWait): String = when (w.reason) {
    "question" -> "answer its question"
    "sign_grant" -> "sign the autonomy grant"
    "confirm" -> if (w.openCards == 1) "confirm 1 command" else "confirm ${w.openCards} commands"
    else -> w.reason.replace('_', ' ')
}

/**
 * One Jev reading: the session and the turn it read (the desktop's
 * `proposalKey`). A later turn is a new reading, so Not waiting on an earlier
 * one does not hide it.
 */
fun proposalKey(row: SessionRow): String = "${row.id}:${row.lastStopAt ?: 0}"

/** What Jev read, under a proposed row: "turn ended with a question". */
internal const val PROPOSED_LINE = "turn ended with a question"

data class InboxUiState(
    /** Rows with a press on the wire. */
    val busy: Set<Long> = emptySet(),
    /** The login Switch account proposes, by session, waiting for the second tap. */
    val switchTargets: Map<Long, HostLogin> = emptyMap(),
    /** A Wait chosen, by session: the reset it waits for. Nothing is sent. */
    val waiting: Map<Long, Long> = emptyMap(),
    /** What the last press on a row did, in words, by session. */
    val notices: Map<Long, String> = emptyMap(),
    /** Jev readings set aside with Not waiting on this phone ([proposalKey]). */
    val setAside: Set<String> = emptySet(),
)

/**
 * The Inbox's inline answers. Retry and the switch's second tap write; the
 * switch's first tap only asks the hub which login has room; Wait and Not
 * waiting change nothing on the hub — they fold the row's buttons on this
 * phone, as the session screen's Wait and the desktop's Not waiting do.
 */
class InboxViewModel(
    private val actions: InboxActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    /** An account uuid's label from `list_accounts`, for "Resumed under …". */
    private val accountName: (String) -> String? = { null },
    /** A host's login is signed out (`signed_out_logins`), by host alias and profile; never offered as a switch target. */
    private val signedOut: (String, String?) -> Boolean = { _, _ -> false },
    /**
     * The accounts a project (by id, and the row's org when it has one)
     * forbids ([dev.claudefleet.mobile.data.forbiddenAccountsIn]); never
     * offered as a switch target.
     */
    private val forbidden: (projectId: Long?, orgId: Long?) -> Set<String> = { _, _ -> emptySet() },
    private val clock: () -> Long = { epochSeconds() },
    /** The level a row is shared with this person at; null for their own ([inboxRowActions]). */
    private val level: (SessionRow) -> String? = { null },
) {
    private val _state = MutableStateFlow(InboxUiState())

    /**
     * Each row's Retry send id, by session, prompt and turn, kept until a
     * press is answered: a press whose answer was lost goes out again under
     * the same id, which the hub replays rather than typing the prompt twice
     * (review w17-4). A later turn that fails is a new failure with an id of
     * its own, and a row that left Needs you drops its id ([keepOnly]), so
     * the hub's cached answer is never replayed for it (review w04-4).
     */
    private val retryIds = mutableMapOf<Triple<Long, String, Long>, String>()
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    /** Retry the last turn of a failed row: [inboxRowActions]'s prompt, sent again. */
    fun retry(row: SessionRow, switchAvailable: Boolean = false): Job? {
        val prompt = inboxRowActions(row, canWrite, switchAvailable, null, level(row)).retryPrompt ?: return null
        val key = Triple(row.id, prompt, row.turnSeq)
        val id = retryIds.getOrPut(key) { newPromptSendId(clock()) }
        return press(row.id, write = true) {
            actions.retry(row.id, prompt, id)
            retryIds.remove(key)
            "Sent the last prompt again."
        }
    }

    /** Switch account, first tap: which login on the row's host has room on another account. Nothing moves yet. */
    fun proposeSwitch(row: SessionRow, switchAvailable: Boolean): Job? {
        if (!inboxRowActions(row, canWrite, switchAvailable, null, level(row)).switchAccount) return null
        return press(row.id) {
            val out = { l: HostLogin -> signedOut(row.hostAlias, l.profile) }
            val h = actions.headroom(row.hostAlias, row.claudeProfile, row.projectId, row.launchModel)
            // Never a login the hub would refuse for the row's project (E_ACCOUNT_FORBIDDEN).
            val off = forbidden(row.projectId, row.orgId)
            val target = if (row.attention?.reason == "no_credentials") {
                h.signedInElsewhere(row.claudeProfile, out, off)
            } else {
                h.forAccount(row.accountUuid, out, off).suggestion
            }
            if (target == null) {
                if (row.attention?.reason == "no_credentials") "No other login on ${row.hostAlias} is signed in with room left."
                else "No other login on ${row.hostAlias} has room left."
            } else {
                _state.update { it.copy(switchTargets = it.switchTargets + (row.id to target)) }
                null
            }
        }
    }

    /** Switch account, second tap: restart under the proposed login. */
    fun confirmSwitch(row: SessionRow, switchAvailable: Boolean): Job? {
        val target = _state.value.switchTargets[row.id] ?: return null
        if (!inboxRowActions(row, canWrite, switchAvailable, null, level(row)).switchAccount) return null
        return press(row.id) {
            actions.restartUnder(row.id, target.profile ?: "")
            _state.update { it.copy(switchTargets = it.switchTargets - row.id) }
            "Resumed under ${loginLabel(target, accountName)}."
        }
    }

    fun cancelSwitch(sessionId: Long) {
        _state.update { it.copy(switchTargets = it.switchTargets - sessionId) }
    }

    /** Wait: fold the row's buttons into "Waiting for the reset"; the session resumes on its own after it. */
    fun waitForReset(sessionId: Long, resetsAt: Long) {
        _state.update { it.copy(waiting = it.waiting + (sessionId to resetsAt), switchTargets = it.switchTargets - sessionId) }
    }

    /** Not waiting: set Jev's reading of [row] aside on this phone. The next turn is a new reading. */
    fun notWaiting(row: SessionRow) {
        _state.update { it.copy(setAside = it.setAside + proposalKey(row)) }
    }

    fun dismissNotice(sessionId: Long) {
        _state.update { it.copy(notices = it.notices - sessionId) }
    }

    /**
     * Forget what this phone said or proposed for rows that left Needs you
     * ([needYou] is the ids still there): a row that fails again hours later
     * is a new failure, not the old press's notice or Switch proposal
     * (review w17-5). A Not waiting is kept by its own key ([proposalKey]).
     */
    fun keepOnly(needYou: Set<Long>) {
        val s = _state.value
        retryIds.keys.removeAll { it.first !in needYou && it.first !in s.busy }
        if (s.notices.keys.all { it in needYou } && s.switchTargets.keys.all { it in needYou } && s.waiting.keys.all { it in needYou }) return
        _state.update {
            it.copy(
                notices = it.notices.filterKeys { id -> id in needYou || id in it.busy },
                switchTargets = it.switchTargets.filterKeys { id -> id in needYou },
                waiting = it.waiting.filterKeys { id -> id in needYou },
            )
        }
    }

    /**
     * One press on one row; [call] answers the row's notice (null: none). A
     * refusal is the notice; a [write] whose outcome is unknown says so
     * ([friendlyWrite]) rather than that it failed.
     */
    private fun press(sessionId: Long, write: Boolean = false, call: suspend () -> String?): Job? {
        if (sessionId in _state.value.busy) return null
        _state.update { it.copy(busy = it.busy + sessionId, notices = it.notices - sessionId) }
        return scope.launch {
            val notice = try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                (if (write) friendlyWrite(t) else friendly(t)).body
            }
            _state.update {
                it.copy(
                    busy = it.busy - sessionId,
                    notices = if (notice != null) it.notices + (sessionId to notice) else it.notices,
                )
            }
        }
    }
}
