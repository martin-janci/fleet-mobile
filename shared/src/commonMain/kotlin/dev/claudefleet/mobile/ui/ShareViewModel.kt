package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.ShareActions
import dev.claudefleet.mobile.model.GrantLevel
import dev.claudefleet.mobile.model.SessionGrant
import dev.claudefleet.mobile.model.ShareTo
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

/** The first contract revision whose sharing tools take the Answer level (Orbit Fleet 11.7). */
const val HUB_CONTRACT_ANSWER_LEVEL: Int = 15

/** Whether the share draft is for a person or an org (its members as of now). */
enum class ShareKind { Person, Org }

data class ShareUiState(
    /** The hub serves the owner's four sharing tools to this token, and the phone may write. */
    val available: Boolean = false,
    /** This person owns the open session: the only one the hub lets share it. */
    val owner: Boolean = false,
    val sessionId: Long? = null,
    val sessionName: String = "",
    val hostAlias: String = "",
    /** The live grants, or null while the first read is out — told apart from the common "nobody". */
    val grants: List<SessionGrant>? = null,
    val kind: ShareKind = ShareKind.Person,
    val recipient: String = "",
    val level: String = GrantLevel.WATCH,
    /** What a share may be made at against this hub: Answer only from revision 15. */
    val levels: List<String> = listOf(GrantLevel.WATCH, GrantLevel.DRIVE),
    /** The recipient key a Revoke is one tap from (two steps, as on the desktop). */
    val confirming: ShareTo? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: Friendly? = null,
) {
    val open: Boolean get() = sessionId != null

    val canAct: Boolean get() = available && owner && !busy

    val canShare: Boolean get() = canAct && recipient.isNotBlank() && level in levels
}

/**
 * The share sheet on the phone (redesign 11.10, "share and watch"): the
 * desktop's `ShareSheet.svelte`, opened from a session's Details. Lists who
 * holds a grant, shares with a person or an org at Watch, Answer or Drive,
 * narrows a grant to Watch and revokes one. The list is re-read after every
 * change rather than patched, because the hub is the authority on the set.
 */
class ShareViewModel(
    private val fleet: FleetState,
    private val actions: ShareActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val sessionId: Long? = null,
        val grants: List<SessionGrant>? = null,
        val kind: ShareKind = ShareKind.Person,
        val recipient: String = "",
        val level: String = GrantLevel.WATCH,
        val confirming: ShareTo? = null,
        val busy: Boolean = false,
        val notice: String? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<ShareUiState> =
        combine(local, fleet.capabilities, fleet.access, fleet.sessions, fleet.hubContract) { l, caps, access, rows, contract ->
            val row = l.sessionId?.let { id -> rows.firstOrNull { it.id == id } }
            ShareUiState(
                available = canWrite && caps.share,
                owner = access.owns(row),
                sessionId = l.sessionId,
                sessionName = row?.displayName ?: "this session",
                hostAlias = row?.hostAlias.orEmpty(),
                grants = l.grants,
                kind = l.kind,
                recipient = l.recipient,
                level = l.level,
                levels = shareLevels(contract),
                confirming = l.confirming,
                busy = l.busy,
                notice = l.notice,
                error = l.error,
            )
        }.stateIn(scope, SharingStarted.Eagerly, ShareUiState())

    /** Open the sheet on [sessionId] and read who it is shared with. */
    fun open(sessionId: Long): Job {
        local.value = Local(sessionId = sessionId)
        return scope.launch { load(sessionId) }
    }

    fun close() {
        local.value = Local()
    }

    fun setKind(kind: ShareKind) = local.update { it.copy(kind = kind, confirming = null) }

    fun setRecipient(text: String) = local.update { it.copy(recipient = text, confirming = null) }

    fun setLevel(level: String) = local.update { it.copy(level = level, confirming = null) }

    fun askRevoke(to: ShareTo) = local.update { it.copy(confirming = to) }

    fun cancelRevoke() = local.update { it.copy(confirming = null) }

    fun dismissError() = local.update { it.copy(error = null) }

    /** Share with the drafted recipient at the drafted level. */
    fun share(): Job = scope.launch {
        val s = state.value
        val id = s.sessionId ?: return@launch
        if (!s.canShare) return@launch
        val name = s.recipient.trim()
        val to = if (s.kind == ShareKind.Org) ShareTo.Org(name) else ShareTo.Person(name)
        perform(id, "Shared with ${toLabel(to)} to ${s.level}.") {
            actions.share(id, to, s.level)
            local.update { it.copy(recipient = "") }
        }
    }

    /** Lower [grant] to watch. */
    fun narrow(grant: SessionGrant): Job = scope.launch {
        val id = state.value.sessionId ?: return@launch
        val to = grant.recipient ?: return@launch
        if (!grant.narrowable) return@launch
        perform(id, "${grant.label} can now only watch.") { actions.narrow(id, to) }
    }

    /** Revoke the grant [askRevoke] is waiting on. */
    fun revoke(): Job = scope.launch {
        val id = state.value.sessionId ?: return@launch
        val to = local.value.confirming ?: return@launch
        perform(id, "Stopped sharing with ${toLabel(to)}.") { actions.revoke(id, to) }
    }

    /**
     * Every write has one shape: re-asked at the call (a revoke can land
     * between a tap and here), run, keep the sheet open on a refusal with the
     * reason, re-read the list on success.
     */
    private suspend fun perform(sessionId: Long, done: String, write: suspend () -> Unit) {
        if (!state.value.canAct) return
        local.update { it.copy(busy = true, error = null, notice = null, confirming = null) }
        try {
            write()
            local.update { it.copy(busy = false, notice = done) }
            load(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = false, error = friendly(t)) }
        }
    }

    private suspend fun load(sessionId: Long) {
        try {
            val grants = actions.access(sessionId)
            local.update { if (it.sessionId == sessionId) it.copy(grants = grants) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { if (it.sessionId == sessionId) it.copy(error = friendly(t)) else it }
        }
    }
}

/** The levels a share may be made at against a hub at [contract]: Answer needs revision 15. */
fun shareLevels(contract: Int?): List<String> =
    if (contract != null && contract >= HUB_CONTRACT_ANSWER_LEVEL) GrantLevel.ALL else listOf(GrantLevel.WATCH, GrantLevel.DRIVE)

private fun toLabel(to: ShareTo): String = when (to) {
    is ShareTo.Person -> to.name
    is ShareTo.Org -> "${to.name}'s members"
}
