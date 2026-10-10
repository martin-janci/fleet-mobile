package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.model.AccountLimit
import dev.claudefleet.mobile.model.Headroom
import dev.claudefleet.mobile.model.HostLogin
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.MissionWait
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.forAccount
import dev.claudefleet.mobile.model.loginLabel
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
    /** Retry the last turn: its prompt again, as a new one. */
    suspend fun retry(sessionId: Long, prompt: String)

    /** Which login on [hostAlias] has room left (`check_account_headroom`). */
    suspend fun headroom(hostAlias: String, profile: String?): Headroom

    /** Restart the session under [profile], resuming its conversation. */
    suspend fun restartUnder(sessionId: Long, profile: String)
}

/** [InboxActions] through the session screen's [SessionActions]. */
class SessionInboxActions(private val actions: SessionActions) : InboxActions {
    override suspend fun retry(sessionId: Long, prompt: String) {
        actions.sendPrompt(sessionId, prompt)
    }

    override suspend fun headroom(hostAlias: String, profile: String?): Headroom = actions.accountHeadroom(hostAlias, profile)

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
): InboxRowActions {
    val mayManage = canWrite && !row.isController
    return when {
        row.attention?.reason == "account_limit" -> InboxRowActions(
            switchAccount = mayManage && switchAvailable,
            waitUntil = limit?.resetsAt,
        )
        inboxWord(row) == dev.claudefleet.mobile.ui.kit.StatusWord.FAILED -> InboxRowActions(
            openLog = true,
            retryPrompt = row.lastPrompt?.trim()?.takeIf {
                it.isNotEmpty() && mayManage && row.claudeStatus == "failed" && row.pendingInput == null
            },
        )
        else -> InboxRowActions()
    }
}

/**
 * The missions waiting on a person (contract 15's `waiting_on`, the
 * desktop's `waitingOf`): active ones only, the longest waiting first.
 */
fun missionWaits(missions: List<Mission>): List<Mission> =
    missions.filter { it.state == "active" && it.waitingOn != null }
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
) {
    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    /** Retry the last turn of a failed row: [inboxRowActions]'s prompt, sent again. */
    fun retry(row: SessionRow, switchAvailable: Boolean = false): Job? {
        val prompt = inboxRowActions(row, canWrite, switchAvailable, null).retryPrompt ?: return null
        return press(row.id) {
            actions.retry(row.id, prompt)
            "Sent the last prompt again."
        }
    }

    /** Switch account, first tap: which login on the row's host has room on another account. Nothing moves yet. */
    fun proposeSwitch(row: SessionRow, switchAvailable: Boolean): Job? {
        if (!inboxRowActions(row, canWrite, switchAvailable, null).switchAccount) return null
        return press(row.id) {
            val target = actions.headroom(row.hostAlias, row.claudeProfile).forAccount(row.accountUuid).suggestion
            if (target == null) {
                "No other login on ${row.hostAlias} has room left."
            } else {
                _state.update { it.copy(switchTargets = it.switchTargets + (row.id to target)) }
                null
            }
        }
    }

    /** Switch account, second tap: restart under the proposed login. */
    fun confirmSwitch(row: SessionRow, switchAvailable: Boolean): Job? {
        val target = _state.value.switchTargets[row.id] ?: return null
        if (!inboxRowActions(row, canWrite, switchAvailable, null).switchAccount) return null
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

    /** One press on one row; [call] answers the row's notice (null: none). A refusal is the notice. */
    private fun press(sessionId: Long, call: suspend () -> String?): Job? {
        if (sessionId in _state.value.busy) return null
        _state.update { it.copy(busy = it.busy + sessionId, notices = it.notices - sessionId) }
        return scope.launch {
            val notice = try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                friendly(t).body
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
